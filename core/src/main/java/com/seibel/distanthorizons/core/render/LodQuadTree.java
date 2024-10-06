/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2023 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.render;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadNode;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadTree;
import com.seibel.distanthorizons.coreapi.util.MathUtil;
import it.unimi.dsi.fastutil.longs.LongIterator;
import org.apache.logging.log4j.Logger;

import javax.annotation.WillNotClose;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This quadTree structure is our core data structure and holds
 * all rendering data.
 */
public class LodQuadTree extends QuadTree<LodRenderSection> implements IDebugRenderable, AutoCloseable
{
	public static final byte TREE_LOWEST_DETAIL_LEVEL = ColumnRenderSource.SECTION_SIZE_OFFSET;
	
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	/** there should only ever be one {@link LodQuadTree} so having the thread static should be fine */
	private static final ThreadPoolExecutor FULL_DATA_RETRIEVAL_QUEUE_THREAD = ThreadUtil.makeSingleThreadPool("QuadTree Full Data Retrieval Queue Populator");
	private static final int WORLD_GEN_QUEUE_UPDATE_DELAY_IN_MS = 1_000;
	
	
	public final int blockRenderDistanceDiameter;
	@WillNotClose
	private final FullDataSourceProviderV2 fullDataSourceProvider;
	
	/**
	 * This holds every {@link DhSectionPos} that should be reloaded next tick. <br>
	 * This is a {@link ConcurrentLinkedQueue} because new sections can be added to this list via the world generator threads.
	 */
	private final ConcurrentLinkedQueue<Long> sectionsToReload = new ConcurrentLinkedQueue<>();
	private final IDhClientLevel level; //FIXME: Proper hierarchy to remove this reference!
	private final ReentrantLock treeReadWriteLock = new ReentrantLock();
	private final AtomicBoolean fullDataRetrievalQueueRunning = new AtomicBoolean(false);
	
	private ArrayList<LodRenderSection> debugRenderSections = new ArrayList<>();
	private ArrayList<LodRenderSection> altDebugRenderSections = new ArrayList<>();
	private final ReentrantLock debugRenderSectionLock = new ReentrantLock();
	
	/** the smallest numerical detail level number that can be rendered */
	private byte maxRenderDetailLevel;
	/** the largest numerical detail level number that can be rendered */
	private byte minRenderDetailLevel;
	
	/** used to calculate when a detail drop will occur */
	private double detailDropOffDistanceUnit;
	/** used to calculate when a detail drop will occur */
	private double detailDropOffLogBase;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public LodQuadTree(
			IDhClientLevel level, int viewDiameterInBlocks,
			int initialPlayerBlockX, int initialPlayerBlockZ,
			FullDataSourceProviderV2 fullDataSourceProvider)
	{
		super(viewDiameterInBlocks, new DhBlockPos2D(initialPlayerBlockX, initialPlayerBlockZ), TREE_LOWEST_DETAIL_LEVEL);
		
		DebugRenderer.register(this, Config.Client.Advanced.Debugging.DebugWireframe.showQuadTreeRenderStatus);
		
		this.level = level;
		this.fullDataSourceProvider = fullDataSourceProvider;
		this.blockRenderDistanceDiameter = viewDiameterInBlocks;
	}
	
	
	
	//=============//
	// tick update //
	//=============//
	
	/**
	 * This function updates the quadTree based on the playerPos and the current game configs (static and global)
	 *
	 * @param playerPos the reference position for the player
	 */
	public void tick(DhBlockPos2D playerPos)
	{
		if (this.level == null)
		{
			// the level hasn't finished loading yet
			// TODO sometimes null pointers still happen, when logging back into a world (maybe the old level isn't null but isn't valid either?)
			return;
		}
		
		
		
		// this shouldn't be updated while the tree is being iterated through
		this.updateDetailLevelVariables();
		
		// don't traverse the tree if it is being modified
		if (this.treeReadWriteLock.tryLock())
		{
			try
			{
				// recenter if necessary, removing out of bounds sections
				this.setCenterBlockPos(playerPos, LodRenderSection::close);
				
				this.updateAllRenderSections(playerPos);
			}
			catch (Exception e)
			{
				LOGGER.error("Quad Tree tick exception for dimension: " + this.level.getLevelWrapper().getDimensionName() + ", exception: " + e.getMessage(), e);
			}
			finally
			{
				this.treeReadWriteLock.unlock();
			}
		}
	}
	private void updateAllRenderSections(DhBlockPos2D playerPos)
	{
		if (Config.Client.Advanced.Debugging.DebugWireframe.showQuadTreeRenderStatus.get())
		{
			try
			{
				// lock to prevent accidentally rendering an array that's being populated/cleared
				this.debugRenderSectionLock.lock();
				
				// swap the debug arrays
				this.debugRenderSections.clear();
				ArrayList<LodRenderSection> temp = this.debugRenderSections;
				this.debugRenderSections = this.altDebugRenderSections;
				this.altDebugRenderSections = temp;
			}
			finally
			{
				this.debugRenderSectionLock.unlock();
			}
		}
		
		
		
		// walk through each root node
		HashSet<LodRenderSection> nodesNeedingRetrieval = new HashSet<>();
		HashSet<LodRenderSection> nodesNeedingLoading = new HashSet<>();
		LongIterator rootPosIterator = this.rootNodePosIterator();
		while (rootPosIterator.hasNext())
		{
			// make sure all root nodes have been created
			long rootPos = rootPosIterator.nextLong();
			if (this.getNode(rootPos) == null)
			{
				this.setValue(rootPos, new LodRenderSection(rootPos, this, this.level, this.fullDataSourceProvider));
			}
			
			QuadNode<LodRenderSection> rootNode = this.getNode(rootPos);
			this.recursivelyUpdateRenderSectionNode(playerPos, rootNode, rootNode, rootNode.sectionPos, false, nodesNeedingRetrieval, nodesNeedingLoading);
		}
		
		
		// full data retrieval (world gen)
		if (!this.fullDataRetrievalQueueRunning.get())
		{
			this.fullDataRetrievalQueueRunning.set(true);
			FULL_DATA_RETRIEVAL_QUEUE_THREAD.execute(() -> this.queueFullDataRetrievalTasks(playerPos, nodesNeedingRetrieval));
		}
		
		
		// reloading is for sections that have been loaded once already
		this.reloadQueuedSections();
		
		// loading is for sections that haven't rendered yet
		this.loadQueuedSections(playerPos, nodesNeedingLoading);
		
	}
	/** @return whether the current position is able to render (note: not if it IS rendering, just if it is ABLE to.) */
	private boolean recursivelyUpdateRenderSectionNode(
			DhBlockPos2D playerPos, 
			QuadNode<LodRenderSection> rootNode, QuadNode<LodRenderSection> quadNode, long sectionPos, 
			boolean parentSectionIsRendering,
			HashSet<LodRenderSection> nodesNeedingRetrieval,
			HashSet<LodRenderSection> nodesNeedingLoading)
	{
		//=====================//
		// get/create the node //
		// and render section  //
		//=====================//
		
		// create the node
		if (quadNode == null && this.isSectionPosInBounds(sectionPos)) // the position bounds should only fail when at the edge of the user's render distance
		{
			rootNode.setValue(sectionPos, new LodRenderSection(sectionPos, this, this.level, this.fullDataSourceProvider));
			quadNode = rootNode.getNode(sectionPos);
		}
		if (quadNode == null)
		{
			// this node must be out of bounds, or there was an issue adding it to the tree
			return false;
		}
		
		// make sure the render section is created (shouldn't be necessary, but just in case)
		LodRenderSection renderSection = quadNode.value;
		if (renderSection == null)
		{
			renderSection = new LodRenderSection(sectionPos, this, this.level, this.fullDataSourceProvider);
			rootNode.setValue(sectionPos, renderSection);
		}
		
		
		
		//===============================//
		// handle enabling, loading,     //
		// and disabling render sections //
		//===============================//
		
		//byte expectedDetailLevel = DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL + 3; // can be used instead of the following logic for testing
		byte expectedDetailLevel = this.calculateExpectedDetailLevel(playerPos, sectionPos);
		expectedDetailLevel = (byte) Math.min(expectedDetailLevel, this.minRenderDetailLevel);
		expectedDetailLevel += DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
		
		if (DhSectionPos.getDetailLevel(sectionPos) > expectedDetailLevel)
		{
			//=======================//
			// detail level too high //
			//=======================//
			
			boolean thisPosIsRendering = renderSection.getRenderingEnabled();
			boolean allChildrenSectionsAreLoaded = true;
			
			// recursively update each child render section
			for (int i = 0; i < 4; i++)
			{
				QuadNode<LodRenderSection> childNode = quadNode.getChildByIndex(i);
				boolean childSectionLoaded = this.recursivelyUpdateRenderSectionNode(playerPos, rootNode, childNode, DhSectionPos.getChildByIndex(sectionPos, i), thisPosIsRendering || parentSectionIsRendering, nodesNeedingRetrieval, nodesNeedingLoading);
				allChildrenSectionsAreLoaded = childSectionLoaded && allChildrenSectionsAreLoaded;
			}
			
			
			if (!allChildrenSectionsAreLoaded)
			{
				// not all child positions are loaded yet, or this section is out of render range
				return thisPosIsRendering;
			}
			else
			{
				// onRenderingDisabled() needs to be fired before the children are enabled so beacons render correctly
				if (renderSection.getRenderingEnabled())
				{
					renderSection.onRenderingDisabled();
					
					// this position's rendering has been disabled due to children being rendered
					DebugRenderer.makeParticle(new DebugRenderer.BoxParticle(new DebugRenderer.Box(renderSection.pos, 128f, 156f, 0.09f, Color.WHITE), 0.2, 32f));
				}
				
				
				// walk back down the tree and enable each child section
				for (int i = 0; i < 4; i++)
				{
					QuadNode<LodRenderSection> childNode = quadNode.getChildByIndex(i);
					this.recursivelyUpdateRenderSectionNode(playerPos, rootNode, childNode, DhSectionPos.getChildByIndex(sectionPos, i), parentSectionIsRendering, nodesNeedingRetrieval, nodesNeedingLoading);
				}
				
				// disabling rendering must be done after the children are enabled
				// otherwise holes may appear in the world, overlaps are less noticeable
				renderSection.setRenderingEnabled(false);
				
				// this section is now being rendered via its children
				return true;
			}
		}
		// TODO this should only equal the expected detail level, the (expectedDetailLevel-1) is a temporary fix to prevent corners from being cut out 
		else if (DhSectionPos.getDetailLevel(sectionPos) == expectedDetailLevel || DhSectionPos.getDetailLevel(sectionPos) == expectedDetailLevel - 1)
		{
			//======================//
			// desired detail level //
			//======================//
			
			
			// prepare this section for rendering
			if (!renderSection.gpuUploadInProgress() && renderSection.renderBuffer == null)
			{
				nodesNeedingLoading.add(renderSection);
			}
			
			// queue world gen if needed
			if (!renderSection.isFullyGenerated())
			{
				nodesNeedingRetrieval.add(renderSection);
			}
			
			// update debug if needed
			if (Config.Client.Advanced.Debugging.DebugWireframe.showQuadTreeRenderStatus.get())
			{
				this.debugRenderSections.add(renderSection);
			}
			
			
			
			// wait for the parent to disable before enabling this section, so we don't have a hole
			if (!parentSectionIsRendering && renderSection.canRender())
			{
				// if rendering is already enabled we don't have to re-enable it
				if (!renderSection.getRenderingEnabled())
				{
					renderSection.setRenderingEnabled(true);
					
					// disabling rendering must be done after the parent is enabled
					// otherwise holes may appear in the world, overlaps are less noticeable
					quadNode.deleteAllChildren((childRenderSection) ->
					{
						if (childRenderSection != null)
						{
							if (childRenderSection.getRenderingEnabled())
							{
								// this position's rendering has been disabled due to a parent rendering
								DebugRenderer.makeParticle(new DebugRenderer.BoxParticle(new DebugRenderer.Box(childRenderSection.pos, 128f, 156f, 0.09f, Color.MAGENTA),0.2, 32f));
							}
							
							childRenderSection.setRenderingEnabled(false);
							childRenderSection.onRenderingDisabled();
							childRenderSection.close();
						}
					});
					
					// onRenderingEnabled() needs to be fired after the children are disabled so beacons render correctly
					renderSection.onRenderingEnabled();
					
				}
			}
			
			return renderSection.canRender();
		}
		else
		{
			throw new IllegalStateException("LodQuadTree shouldn't be updating renderSections below the expected detail level: [" + expectedDetailLevel + "].");
		}
	}
	private void reloadQueuedSections()
	{
		Long pos;
		HashSet<Long> positionsToRequeue = new HashSet<>();
		while ((pos = this.sectionsToReload.poll()) != null)
		{
			// walk up the tree until we hit the root node
			// this is done so any high detail changes flow up to the lower detail render sections as well
			while (DhSectionPos.getDetailLevel(pos) <= this.treeMinDetailLevel)
			{
				if (positionsToRequeue.contains(pos))
				{
					// don't attempt to re-load positions that are already in the process of reloading
					break;
				}
				
				try
				{
					// We need to update every non-null section, including those that aren't currently rendering.
					// If this isn't done, and the player moves so a lower quality section is now being rendered,
					// that section will not have updated correctly and may refuse to load in at all.
					LodRenderSection renderSection = this.getValue(pos);
					if (renderSection != null)
					{
						if (!renderSection.gpuUploadInProgress())
						{
							renderSection.uploadRenderDataToGpuAsync();
						}
						else
						{
							// if a section is already loading we need to wait to trigger it again
							// if we don't trigger it again the LOD will be out of date
							// and may be invisible/missing
							positionsToRequeue.add(pos);
							break;
						}
					}
				}
				catch (IndexOutOfBoundsException e)
				{ /* the section is now out of bounds, it doesn't need to be reloaded */ }
				
				pos = DhSectionPos.getParentPos(pos);
			}
		}
		this.sectionsToReload.addAll(positionsToRequeue);
	}
	private void loadQueuedSections(DhBlockPos2D playerPos, HashSet<LodRenderSection> nodesNeedingLoading)
	{
		ArrayList<LodRenderSection> loadSectionList = new ArrayList<>(nodesNeedingLoading);
		loadSectionList.sort((a, b) ->
		{
			int aDist = DhSectionPos.getManhattanBlockDistance(a.pos, playerPos);
			int bDist = DhSectionPos.getManhattanBlockDistance(b.pos, playerPos);
			return Integer.compare(aDist, bDist);
		});
		
		for (int i = 0; i < loadSectionList.size(); i++)
		{
			LodRenderSection renderSection = loadSectionList.get(i);
			if (!renderSection.gpuUploadInProgress() && renderSection.renderBuffer == null)
			{
				renderSection.uploadRenderDataToGpuAsync();
			}
		}
	}
	
	
	
	//====================//
	// detail level logic //
	//====================//
	
	/**
	 * This method will compute the detail level based on player position and section pos
	 * Override this method if you want to use a different algorithm
	 *
	 * @param playerPos player position as a reference for calculating the detail level
	 * @param sectionPos section position
	 * @return detail level of this section pos
	 */
	public byte calculateExpectedDetailLevel(DhBlockPos2D playerPos, long sectionPos) { return this.getDetailLevelFromDistance(playerPos.dist(DhSectionPos.getCenterBlockPosX(sectionPos), DhSectionPos.getCenterBlockPosZ(sectionPos))); }
	private byte getDetailLevelFromDistance(double distance)
	{
		double maxDetailDistance = this.getDrawDistanceFromDetail(Byte.MAX_VALUE - 1);
		if (distance > maxDetailDistance)
		{
			return Byte.MAX_VALUE - 1;
		}
		
		
		int detailLevel = (int) (Math.log(distance / this.detailDropOffDistanceUnit) / this.detailDropOffLogBase);
		return (byte) MathUtil.clamp(this.maxRenderDetailLevel, detailLevel, Byte.MAX_VALUE - 1);
	}
	private double getDrawDistanceFromDetail(int detail)
	{
		if (detail <= this.maxRenderDetailLevel)
		{
			return 0;
		}
		else if (detail >= Byte.MAX_VALUE)
		{
			return this.blockRenderDistanceDiameter * 2;
		}
		
		
		double base = Config.Client.Advanced.Graphics.Quality.horizontalQuality.get().quadraticBase;
		return Math.pow(base, detail) * this.detailDropOffDistanceUnit;
	}
	
	private void updateDetailLevelVariables()
	{
		this.detailDropOffDistanceUnit = Config.Client.Advanced.Graphics.Quality.horizontalQuality.get().distanceUnitInBlocks * LodUtil.CHUNK_WIDTH;
		this.detailDropOffLogBase = Math.log(Config.Client.Advanced.Graphics.Quality.horizontalQuality.get().quadraticBase);
		
		this.maxRenderDetailLevel = Config.Client.Advanced.Graphics.Quality.maxHorizontalResolution.get().detailLevel;
		
		// The minimum detail level is done to prevent single corner sections rendering 1 detail level lower than the others.
		// If not done corners may not be flush with the other LODs, which looks bad.
		byte minSectionDetailLevel = this.getDetailLevelFromDistance(this.blockRenderDistanceDiameter); // get the minimum allowed detail level
		minSectionDetailLevel -= 1; // -1 so corners can't render lower than their adjacent neighbors. space
		minSectionDetailLevel = (byte) Math.min(minSectionDetailLevel, this.treeMinDetailLevel); // don't allow rendering lower detail sections than what the tree contains
		this.minRenderDetailLevel = (byte) Math.max(minSectionDetailLevel, this.maxRenderDetailLevel); // respect the user's selected max resolution if it is lower detail (IE they want 2x2 block, but minSectionDetailLevel is specifically for 1x1 block render resolution)
	}
	
	
	
	//=============//
	// render data //
	//=============//
	
	/**
	 * Re-creates the color, render data.
	 * This method should be called after resource packs are changed or LOD settings are modified.
	 */
	public void clearRenderDataCache()
	{
		if (this.treeReadWriteLock.tryLock()) // TODO make async, can lock render thread
		{
			try
			{
				LOGGER.info("Disposing render data...");
				
				// clear the tree
				Iterator<QuadNode<LodRenderSection>> nodeIterator = this.nodeIterator();
				while (nodeIterator.hasNext())
				{
					QuadNode<LodRenderSection> quadNode = nodeIterator.next();
					if (quadNode.value != null)
					{
						quadNode.value.close();
						quadNode.value = null;
					}
				}
				
				LOGGER.info("Render data cleared, please wait a moment for everything to reload...");
			}
			catch (Exception e)
			{
				LOGGER.error("Unexpected error when clearing LodQuadTree render cache: " + e.getMessage(), e);
			}
			finally
			{
				this.treeReadWriteLock.unlock();
			}
		}
	}
	
	/**
	 * Can be called whenever a render section's data needs to be refreshed. <br>
	 * This should be called whenever a world generation task is completed or if the connected server has new data to show.
	 */
	public void reloadPos(long pos)
	{
		this.sectionsToReload.add(pos);
		
		// the adjacent locations also need to be updated to make sure lighting
		// and water updates correctly, otherwise oceans may have walls
		// and lights may not show up over LOD borders
		for (EDhDirection direction : EDhDirection.ADJ_DIRECTIONS)
		{
			this.sectionsToReload.add(DhSectionPos.getAdjacentPos(pos, direction));
		}
	}
	
	
	
	//=================================//
	// full data retrieval (world gen) //
	//=================================//
	
	private void queueFullDataRetrievalTasks(DhBlockPos2D playerPos, HashSet<LodRenderSection> nodesNeedingRetrieval)
	{
		try
		{
			// add a slight delay since we don't need to check the world gen queue every tick
			Thread.sleep(WORLD_GEN_QUEUE_UPDATE_DELAY_IN_MS);
			
			// sort the nodes from nearest to farthest
			ArrayList<LodRenderSection> nodeList = new ArrayList<>(nodesNeedingRetrieval);
			nodeList.sort((a, b) ->
			{
				int aDist = DhSectionPos.getManhattanBlockDistance(a.pos, playerPos);
				int bDist = DhSectionPos.getManhattanBlockDistance(b.pos, playerPos);
				return Integer.compare(aDist, bDist);
			});
			
			// add retrieval tasks to the queue
			for (int i = 0; i < nodeList.size(); i++)
			{
				LodRenderSection renderSection = nodeList.get(i);
				if (!this.fullDataSourceProvider.canQueueRetrieval())
				{
					break;
				}
				
				renderSection.tryQueuingMissingLodRetrieval();
			}
			
			// calculate an estimate for the max number of tasks for the queue
			int totalWorldGenCount = 0;
			for (int i = 0; i < nodeList.size(); i++)
			{
				LodRenderSection renderSection = nodeList.get(i);
				if (!renderSection.missingPositionsCalculated())
				{
					// may be higher than the actual amount
					totalWorldGenCount += this.fullDataSourceProvider.getMaxPossibleRetrievalPositionCountForPos(renderSection.pos);
				}
				else
				{
					totalWorldGenCount += renderSection.ungeneratedPositionCount();
				}
			}
			this.fullDataSourceProvider.setTotalRetrievalPositionCount(totalWorldGenCount);
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected error: "+e.getMessage(), e);
		}
		finally
		{
			this.fullDataRetrievalQueueRunning.set(false);
		}
	}
	
	
	
	//===========//
	// debugging //
	//===========//
	
	@Override
	public void debugRender(DebugRenderer debugRenderer)
	{
		try
		{
			// lock to prevent accidentally rendering the array that's being cleared
			this.debugRenderSectionLock.lock();
			
			
			for (int i = 0; i < this.debugRenderSections.size(); i++)
			{
				LodRenderSection renderSection = this.debugRenderSections.get(i);
				
				Color color = Color.BLACK;
				if (renderSection.gpuUploadInProgress())
				{
					color = Color.ORANGE;
				}
				else if (renderSection.renderBuffer == null)
				{
					// uploaded but the buffer is missing
					color = Color.PINK;
				}
				else if (renderSection.renderBuffer.hasNonNullVbos())
				{
					if (renderSection.renderBuffer.vboBufferCount() != 0)
					{
						color = Color.GREEN;
					}
					else
					{
						// This section is probably rendering an empty chunk
						color = Color.RED;
					}
				}
				
				debugRenderer.renderBox(new DebugRenderer.Box(renderSection.pos, 400, 400f, Objects.hashCode(this), 0.05f, color));
			}
		}
		finally
		{
			this.debugRenderSectionLock.unlock();
		}
	}
	
	
	
	//==============//
	// base methods //
	//==============//
	
	@Override
	public void close()
	{
		LOGGER.info("Shutting down " + LodQuadTree.class.getSimpleName() + "...");
		
		DebugRenderer.unregister(this, Config.Client.Advanced.Debugging.DebugWireframe.showQuadTreeRenderStatus);
		
		Iterator<QuadNode<LodRenderSection>> nodeIterator = this.nodeIterator();
		while (nodeIterator.hasNext())
		{
			QuadNode<LodRenderSection> quadNode = nodeIterator.next();
			if (quadNode.value != null)
			{
				quadNode.value.close();
				quadNode.value = null;
			}
		}
		
		LOGGER.info("Finished shutting down " + LodQuadTree.class.getSimpleName());
	}
	
}
