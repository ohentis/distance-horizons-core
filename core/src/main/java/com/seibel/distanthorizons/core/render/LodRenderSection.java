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
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.ColumnRenderBufferBuilder;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import com.seibel.distanthorizons.core.dataObjects.transformers.FullDataToRenderDataTransformer;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.glObject.GLProxy;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.ColumnRenderBuffer;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.util.ListUtil;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.apache.logging.log4j.Logger;

import javax.annotation.WillNotClose;
import java.awt.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A render section represents an area that could be rendered.
 * For more information see {@link LodQuadTree}.
 */
public class LodRenderSection implements IDebugRenderable, AutoCloseable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	
	
	
	public final long pos;
	
	private final IDhClientLevel level;
	@WillNotClose
	private final FullDataSourceProviderV2 fullDataSourceProvider;
	private final LodQuadTree quadTree;
	
	
	private boolean renderingEnabled = false;
	private boolean canRender = false;
	
	/** this reference is necessary so we can determine what VBO to render */
	public ColumnRenderBuffer renderBuffer; 
	
	
	/** 
	 * Encapsulates everything between pulling data from the database (including neighbors)
	 * up to the point when geometry data is uploaded to the GPU.
	 */
	private CompletableFuture<Void> buildAndUploadRenderDataToGpuFuture = null;
	/** 
	 * Represents just building the {@link LodQuadBuilder}. <br>
	 * Separate from {@link LodRenderSection#bufferUploadFuture} because they run on
	 * different thread pools and need to be canceled separately.
	 */
	private CompletableFuture<LodQuadBuilder> bufferBuildFuture = null;
	/** 
	 * Represents just uploading the {@link LodQuadBuilder} to the GPU. <br>
	 * Separate from {@link LodRenderSection#bufferBuildFuture} because they run on
	 * different thread pools and need to be canceled separately.
	 */
	private CompletableFuture<ColumnRenderBuffer> bufferUploadFuture = null;
	
	private final ReentrantLock getRenderSourceLock = new ReentrantLock();
	/** Stored as a class variable so we can reuse it's result across multiple LOD loads if necessary */
	private CompletableFuture<ColumnRenderSource> renderSourceLoadingRefFuture = null;
	
	private boolean missingPositionsCalculated = false;
	/** should be an empty array if no positions need to be generated */
	private LongArrayList missingGenerationPos = null;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public LodRenderSection(long pos, LodQuadTree quadTree, IDhClientLevel level, FullDataSourceProviderV2 fullDataSourceProvider)
	{
		this.pos = pos;
		this.quadTree = quadTree;
		this.level = level;
		this.fullDataSourceProvider = fullDataSourceProvider;
		
		DebugRenderer.register(this, Config.Client.Advanced.Debugging.DebugWireframe.showRenderSectionStatus);
	}
	
	
	
	//======================================//
	// render data generation and uploading //
	//======================================//
	
	public synchronized void uploadRenderDataToGpuAsync()
	{
		if (!GLProxy.hasInstance())
		{
			// it's possible to try uploading buffers before the GLProxy has been initialized
			// which would cause the system to crash
			return;
		}
		
		if (this.buildAndUploadRenderDataToGpuFuture != null)
		{
			// don't accidentally queue multiple uploads at the same time
			return;
		}
		
		ThreadPoolExecutor executor = ThreadPoolUtil.getFileHandlerExecutor();
		if (executor == null || executor.isTerminated())
		{
			return;
		}
		
		try
		{
			this.buildAndUploadRenderDataToGpuFuture = CompletableFuture.runAsync(() ->
			{
				try
				{
					this.loadRenderDataAsync()
						.thenCompose((loadedRenderSources) ->
						{
							try
							{
								ColumnRenderSource thisRenderSource = loadedRenderSources.getThisRenderSource();
								if (thisRenderSource != null && !thisRenderSource.isEmpty())
								{
									CompletableFuture<LodQuadBuilder> buildDataFuture = this.buildNewRenderDataAsync(thisRenderSource, loadedRenderSources);
									buildDataFuture.thenRun(() -> 
									{
										ColumnRenderSource.DATA_SOURCE_POOL.returnPooledDataSource(thisRenderSource);
										ArrayList<ColumnRenderSource> adjacentSourceList = loadedRenderSources.getAdjacentRenderSourceList();
										for (int i = 0; i < adjacentSourceList.size(); i++)
										{
											ColumnRenderSource.DATA_SOURCE_POOL.returnPooledDataSource(adjacentSourceList.get(i));
										}
									});
									return buildDataFuture;
								}
								else
								{
									// nothing needs to be rendered
									this.canRender = false;
									this.buildAndUploadRenderDataToGpuFuture = null;
									this.bufferBuildFuture = null;
									return CompletableFuture.completedFuture(null);
								}
							}
							catch (Exception e)
							{
								// exception handling is done here since attempting to do so in the final future's
								// .exceptionally() block doesn't return the correct stack traces, making debugging impossible
								this.handleException(e);
								throw e;
							}
						})
						.thenCompose((lodQuadBuilder) ->
						{
							try
							{
								// can be null if there was a problem or if there's nothing to render
								if (lodQuadBuilder != null)
								{
									return this.uploadToGpuAsync(lodQuadBuilder);
								}
								else
								{
									return CompletableFuture.completedFuture(null);
								}
							}
							catch (Exception e)
							{
								this.handleException(e);
								throw e;
							}
						});
				}
				catch (Exception e)
				{
					// this catch is just for the first loadRenderDataAsync(),
					// each subsequent method has their own handleException() block.
					this.handleException(e);
				}
			}, executor);
		}
		catch (RejectedExecutionException ignore)
		{ /* the thread pool was probably shut down because it's size is being changed, just wait a sec and it should be back */ }
	}
	private CompletableFuture<LoadedRenderSourcesFutureWrapper> loadRenderDataAsync()
	{
		CompletableFuture<ColumnRenderSource> thisRenderSourceLoadFuture = this.getRenderSourceAsync();
		ArrayList<CompletableFuture<ColumnRenderSource>> adjRenderSourceLoadRefFutures = this.getNeighborRenderSourcesAsync();
		
		
		// wait for all futures to complete together,
		// merging the futures makes loading significantly faster than loading this position then loading its neighbors
		ArrayList<CompletableFuture<ColumnRenderSource>> futureList = new ArrayList<>();
		futureList.add(thisRenderSourceLoadFuture);
		futureList.addAll(adjRenderSourceLoadRefFutures);
		CompletableFuture<Void> allLoadedFuture = CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0]));
		
		return allLoadedFuture.thenApply((voidObj) -> new LoadedRenderSourcesFutureWrapper(allLoadedFuture, thisRenderSourceLoadFuture, adjRenderSourceLoadRefFutures));
	}
	private CompletableFuture<LodQuadBuilder> buildNewRenderDataAsync(
			ColumnRenderSource thisRenderSource,
			LoadedRenderSourcesFutureWrapper loadedRenderSources)
	{
		ColumnRenderSource[] adjacentRenderSections = new ColumnRenderSource[EDhDirection.ADJ_DIRECTIONS.length];
		boolean[] adjIsSameDetailLevel = new boolean[EDhDirection.ADJ_DIRECTIONS.length];
		for (int i = 0; i < EDhDirection.ADJ_DIRECTIONS.length; i++)
		{
			adjacentRenderSections[i] = loadedRenderSources.getAdjacentRenderSource(i);
			
			// if the adjacent position isn't the same detail level the buffer building logic
			// will need to be slightly different in order to reduce holes in the LODs
			EDhDirection direction = EDhDirection.ADJ_DIRECTIONS[i];
			adjIsSameDetailLevel[direction.ordinal() - 2] = this.isAdjacentPosSameDetailLevel(direction);
		}
		
		if (this.bufferBuildFuture != null)
		{
			// shouldn't normally happen, but just in case canceling the previous future
			// prevents the CPU from working on something that won't be used
			this.bufferBuildFuture.cancel(true);
		}
		this.bufferBuildFuture = ColumnRenderBufferBuilder.buildBuffersAsync(this.level, thisRenderSource, adjacentRenderSections, adjIsSameDetailLevel);
		return this.bufferBuildFuture;
	}
	private CompletableFuture<Void> uploadToGpuAsync(LodQuadBuilder lodQuadBuilder)
	{
		if (this.bufferUploadFuture != null)
		{
			// shouldn't normally happen, but just in case canceling the previous future
			// prevents the CPU from working on something that won't be used
			this.bufferUploadFuture.cancel(true);
		}
		
		this.bufferUploadFuture = ColumnRenderBufferBuilder.uploadBuffersAsync(this.level, this.pos, lodQuadBuilder);
		return this.bufferUploadFuture.thenCompose((buffer) ->
		{
			ColumnRenderBuffer previousBuffer = this.renderBuffer;
			
			// upload complete, clean up the old data if 
			this.renderBuffer = buffer;
			this.canRender = (buffer != null);
			this.buildAndUploadRenderDataToGpuFuture = null;
			this.bufferBuildFuture = null;
			
			if (previousBuffer != null)
			{
				previousBuffer.close();
			}
			
			return null;
		});
	}
	
	
	
	//=====================//
	// render data helpers //
	//=====================//
	
	/** Should be called on the {@link ThreadPoolUtil#getFileHandlerExecutor()} */
	private ArrayList<CompletableFuture<ColumnRenderSource>> getNeighborRenderSourcesAsync()
	{
		ArrayList<CompletableFuture<ColumnRenderSource>> futureList = ListUtil.createEmptyList(EDhDirection.ADJ_DIRECTIONS.length);
		
		for (int i = 0; i < EDhDirection.ADJ_DIRECTIONS.length; i++)
		{
			EDhDirection direction = EDhDirection.ADJ_DIRECTIONS[i];
			int arrayIndex = direction.ordinal() - 2;
			
			long adjPos = DhSectionPos.getAdjacentPos(this.pos, direction);
			try
			{
				LodRenderSection adjRenderSection = this.quadTree.getValue(adjPos);
				if (adjRenderSection != null)
				{
					futureList.set(arrayIndex, adjRenderSection.getRenderSourceAsync());
				}
			}
			catch (IndexOutOfBoundsException ignore) {}
			
			if (futureList.get(arrayIndex) == null)
			{
				futureList.set(arrayIndex, CompletableFuture.completedFuture(null));
			}
		}
		
		return futureList;
	}
	
	/** Will try to return the same {@link CompletableFuture} if multiple requests are made for the same position */
	private CompletableFuture<ColumnRenderSource> getRenderSourceAsync()
	{
		try
		{
			this.getRenderSourceLock.lock();
			
			ThreadPoolExecutor executor = ThreadPoolUtil.getFileHandlerExecutor();
			if (executor == null || executor.isTerminated())
			{
				return CompletableFuture.completedFuture(null);
			}
			
			this.renderSourceLoadingRefFuture = CompletableFuture.supplyAsync(() ->
			{
				try (FullDataSourceV2 fullDataSource = this.fullDataSourceProvider.get(this.pos))
				{
					ColumnRenderSource renderSource = FullDataToRenderDataTransformer.transformFullDataToRenderSource(fullDataSource, this.level);
					this.renderSourceLoadingRefFuture = null;
					return renderSource;
				}
				catch (Exception e)
				{
					LOGGER.warn("Unable to get render source " + DhSectionPos.toString(this.pos) + ", error: " + e.getMessage(), e);
					this.renderSourceLoadingRefFuture = null;
					return null;
				}
			}, executor);
			return this.renderSourceLoadingRefFuture;
		}
		finally
		{
			this.getRenderSourceLock.unlock();
		}
	}
	private boolean isAdjacentPosSameDetailLevel(EDhDirection direction)
	{
		long adjPos = DhSectionPos.getAdjacentPos(this.pos, direction);
		byte detailLevel = this.quadTree.calculateExpectedDetailLevel(new DhBlockPos2D(MC.getPlayerBlockPos()), adjPos);
		detailLevel += DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
		return detailLevel == DhSectionPos.getDetailLevel(this.pos);
	}
	
	private void handleException(Throwable e)
	{
		LOGGER.error("Unexpected error in LodRenderSection loading, Error: " + e.getMessage(), e);
		this.buildAndUploadRenderDataToGpuFuture = null;
		this.bufferBuildFuture = null;
	}
	
	
	
	//========================//
	// getters and properties //
	//========================//
	
	public boolean canRender() { return this.canRender; }
	
	public boolean getRenderingEnabled() { return this.renderingEnabled; }
	/**
	 * Separate from {@link LodRenderSection#onRenderingEnabled} and {@link LodRenderSection#onRenderingDisabled}
	 * since we need to trigger external changes in disabled -> enabled order
	 * so beacons are removed and then re-added.
	 * However, to prevent holes in the world when disabling sections we need to
	 * enable the new section(s) first before disabling the old one(s).
	 */
	public void setRenderingEnabled(boolean enabled) { this.renderingEnabled = enabled;}
	
	/** @see LodRenderSection#setRenderingEnabled */
	public void onRenderingEnabled() { this.level.loadBeaconBeamsInPos(this.pos); }
	/** @see LodRenderSection#setRenderingEnabled */
	public void onRenderingDisabled() 
	{
		this.level.unloadBeaconBeamsInPos(this.pos);
		
		if (Config.Client.Advanced.Debugging.DebugWireframe.showRenderSectionStatus.get())
		{
			// show that this position has just been disabled
			DebugRenderer.makeParticle(
					new DebugRenderer.BoxParticle(
							new DebugRenderer.Box(this.pos, 128f, 156f, 0.09f, Color.CYAN.darker()),
							0.2, 32f
					)
			);
		}
	}
	
	
	public boolean gpuUploadInProgress() { return this.buildAndUploadRenderDataToGpuFuture != null; }
	
	
	
	//=================================//
	// full data retrieval (world gen) //
	//=================================//
	
	public boolean isFullyGenerated() { return this.missingPositionsCalculated && this.missingGenerationPos.isEmpty(); }
	public boolean missingPositionsCalculated() { return this.missingPositionsCalculated; }
	public int ungeneratedPositionCount() { return (this.missingGenerationPos != null) ? this.missingGenerationPos.size() : 0; }
	
	public void tryQueuingMissingLodRetrieval()
	{
		if (this.fullDataSourceProvider.canRetrieveMissingDataSources() && this.fullDataSourceProvider.canQueueRetrieval())
		{
			// calculate the missing positions if not already done
			if (!this.missingPositionsCalculated)
			{
				this.missingGenerationPos = this.fullDataSourceProvider.getPositionsToRetrieve(this.pos);
				if (this.missingGenerationPos != null)
				{
					this.missingPositionsCalculated = true;
				}
			}
			
			// if the missing positions were found, queue them
			if (this.missingGenerationPos != null)
			{
				// queue from last to first to prevent shifting the array unnecessarily
				for (int i = this.missingGenerationPos.size() - 1; i >= 0; i--)
				{
					if (!this.fullDataSourceProvider.canQueueRetrieval())
					{
						// the data source provider isn't accepting any more jobs
						break;
					}
					
					long pos = this.missingGenerationPos.removeLong(i);
					boolean positionQueued = this.fullDataSourceProvider.queuePositionForRetrieval(pos);
					if (!positionQueued)
					{
						// shouldn't normally happen, but just in case
						this.missingGenerationPos.add(pos);
						break;
					}
				}
			}
		}
	}
	
	
	
	//==============//
	// base methods //
	//==============//
	
	@Override
	public String toString()
	{
		return  "pos=[" + DhSectionPos.toString(this.pos) + "] " +
				"enabled=[" + this.renderingEnabled + "] " +
				"uploading=[" + this.gpuUploadInProgress() + "] ";
	}
	
	@Override
	public void close()
	{
		DebugRenderer.unregister(this, Config.Client.Advanced.Debugging.DebugWireframe.showRenderSectionStatus);
		
		if (Config.Client.Advanced.Debugging.DebugWireframe.showRenderSectionStatus.get())
		{
			// show a particle for the closed section
			DebugRenderer.makeParticle(
				new DebugRenderer.BoxParticle(
					new DebugRenderer.Box(this.pos, 128f, 156f, 0.09f, Color.RED.darker()),
					0.5, 32f
				)
			);
		}
		
		
		this.level.unloadBeaconBeamsInPos(this.pos);
		
		if (this.renderBuffer != null)
		{
			this.renderBuffer.close();
		}
		
		// cancel all in-progress futures since they aren't needed any more
		if (this.buildAndUploadRenderDataToGpuFuture != null)
		{
			this.buildAndUploadRenderDataToGpuFuture.cancel(true);
		}
		if (this.bufferBuildFuture != null)
		{
			this.bufferBuildFuture.cancel(true);
		}
		if (this.bufferUploadFuture != null)
		{
			this.bufferUploadFuture.cancel(true);
		}
		
		
		
		// remove any active world gen requests that may be for this position
		ThreadPoolExecutor executor = ThreadPoolUtil.getCleanupExecutor();
		if (executor != null && !executor.isTerminated())
		{
			// while this should generally be a fast operation 
			// this is run on a separate thread to prevent lag on the render thread
			
			try
			{
				executor.execute(() -> this.fullDataSourceProvider.removeRetrievalRequestIf((genPos) -> DhSectionPos.contains(this.pos, genPos)));
			}
			catch (RejectedExecutionException ignore)
			{ /* If this happens that means everything is already shut down and no additional cleanup will be necessary */ }
		}
	}
	
	@Override
	public void debugRender(DebugRenderer debugRenderer)
	{
		Color color = Color.red;
		if (this.renderingEnabled)
		{
			color = Color.green;
		}
		else if (this.buildAndUploadRenderDataToGpuFuture != null)
		{
			color = Color.yellow;
		}
		else if (this.canRender)
		{
			color = Color.cyan;
		}
		
		debugRenderer.renderBox(new DebugRenderer.Box(this.pos, 400, 8f, Objects.hashCode(this), 0.1f, color));
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	/** Used to easily pass around the loaded {@link ColumnRenderSource}'s. */
	private static class LoadedRenderSourcesFutureWrapper
	{
		//private final CompletableFuture<Void> future;
		private final CompletableFuture<ColumnRenderSource> thisRenderSourceFuture;
		private final ArrayList<CompletableFuture<ColumnRenderSource>> adjacentRenderSourceFutures;
		
		
		
		public LoadedRenderSourcesFutureWrapper(CompletableFuture<Void> future, CompletableFuture<ColumnRenderSource> thisRenderSourceFuture, ArrayList<CompletableFuture<ColumnRenderSource>> adjacentRenderSourceFutures)
		{
			//this.future = future;
			this.thisRenderSourceFuture = thisRenderSourceFuture;
			this.adjacentRenderSourceFutures = adjacentRenderSourceFutures;
		}
		
		
		
		//public CompletableFuture<Void> getFuture() { return this.future; }
		public ColumnRenderSource getThisRenderSource() { return this.thisRenderSourceFuture != null ? this.thisRenderSourceFuture.getNow(null) : null; }
		public ColumnRenderSource getAdjacentRenderSource(int i)
		{
			CompletableFuture<ColumnRenderSource> future = this.adjacentRenderSourceFutures.get(i);
			return future != null ? future.getNow(null) : null;
		}
		public ArrayList<ColumnRenderSource> getAdjacentRenderSourceList()
		{
			ArrayList<ColumnRenderSource> list = new ArrayList<>();
			for (int i = 0; i < this.adjacentRenderSourceFutures.size(); i++)
			{
				list.add(this.getAdjacentRenderSource(i));
			}			
			return list;
		}
	}
	
}
