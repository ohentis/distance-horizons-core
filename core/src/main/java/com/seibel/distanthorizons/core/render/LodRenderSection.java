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
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.annotation.WillNotClose;
import java.awt.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
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
	private ReferencedFutureWrapper renderSourceLoadingRefFuture = null;
	/** Stored as a class variable so we can decrement reference counts as each {@link LodRenderSection} finishes using them. */
	private ReferencedFutureWrapper[] adjacentLoadRefFutures;
	
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
	
	
	
	//===============================//
	// render data loading/uploading //
	//===============================//
	
	// TODO cleanup, there's a lot of nested futures and duplicate error handling here and it's hard to read
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
		
		this.buildAndUploadRenderDataToGpuFuture = CompletableFuture.runAsync(() -> 
		{
			//==================//
			// load render data //
			//==================//
			
			this.tryDecrementingLoadFutureArray(this.adjacentLoadRefFutures);
			
			ReferencedFutureWrapper thisRenderSourceLoadFuture = this.getRenderSourceAsync();
			ReferencedFutureWrapper[] adjRenderSourceLoadRefFutures = this.getNeighborRenderSourcesAsync();
			
			
			// wait for all futures to complete together,
			// merging the futures makes loading significantly faster than loading this position then loading its neighbors
			ArrayList<CompletableFuture<ColumnRenderSource>> futureList = new ArrayList<>();
			futureList.add(thisRenderSourceLoadFuture.future);
			for (ReferencedFutureWrapper refFuture : adjRenderSourceLoadRefFutures)
			{
				futureList.add(refFuture.future);
			}
			
			CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).thenAccept((voidObj) ->
			{
				try
				{
					ColumnRenderSource renderSource = thisRenderSourceLoadFuture.future.get();
					if (renderSource == null || renderSource.isEmpty())
					{
						thisRenderSourceLoadFuture.decrementRefCount();
						for (ReferencedFutureWrapper futureWrapper : adjRenderSourceLoadRefFutures)
						{
							futureWrapper.decrementRefCount();
						}
						
						// nothing needs to be rendered
						this.canRender = false;
						this.buildAndUploadRenderDataToGpuFuture = null;
						this.bufferBuildFuture = null;
						return;
					}
					
					
					
					//=======================//
					// build new render data //
					//=======================//
					
					try
					{
						ColumnRenderBuffer previousBuffer = this.renderBuffer;
						
						ColumnRenderSource[] adjacentRenderSections = new ColumnRenderSource[EDhDirection.ADJ_DIRECTIONS.length];
						boolean[] adjIsSameDetailLevel = new boolean[EDhDirection.ADJ_DIRECTIONS.length];
						for (int i = 0; i < EDhDirection.ADJ_DIRECTIONS.length; i++)
						{
							adjacentRenderSections[i] = adjRenderSourceLoadRefFutures[i].future.getNow(null);
							
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
						this.bufferBuildFuture = ColumnRenderBufferBuilder.buildBuffersAsync(this.level, renderSource, adjacentRenderSections, adjIsSameDetailLevel);
						this.bufferBuildFuture.thenAccept((lodQuadBuilder) ->
						{
							
							
							
							//===================================//
							// upload new render data to the GPU //
							//===================================//
							
							if (this.bufferUploadFuture != null)
							{
								// shouldn't normally happen, but just in case canceling the previous future
								// prevents the CPU from working on something that won't be used
								this.bufferUploadFuture.cancel(true);
							}
							this.bufferUploadFuture = ColumnRenderBufferBuilder.uploadBuffersAsync(this.level, renderSource, lodQuadBuilder);
							this.bufferUploadFuture.thenAccept((buffer) ->
							{
								// upload complete, clean up the old data if 
								this.renderBuffer = buffer;
								this.canRender = (buffer != null);
								this.buildAndUploadRenderDataToGpuFuture = null;
								this.bufferBuildFuture = null;
								
								
								if (previousBuffer != null)
								{
									previousBuffer.close();
								}
								
								thisRenderSourceLoadFuture.decrementRefCount();
								this.tryDecrementingLoadFutureArray(adjRenderSourceLoadRefFutures);
								this.adjacentLoadRefFutures = null;
							});
						});
					}
					catch (Exception e)
					{
						thisRenderSourceLoadFuture.decrementRefCount();
						this.tryDecrementingLoadFutureArray(adjRenderSourceLoadRefFutures);
						this.adjacentLoadRefFutures = null;
						
						LOGGER.error("Unexpected error in LodRenderSection loading, Error: "+e.getMessage(), e);
						this.buildAndUploadRenderDataToGpuFuture = null;
						this.bufferBuildFuture = null;
					}
				}
				catch (Exception e)
				{
					thisRenderSourceLoadFuture.decrementRefCount();
					this.tryDecrementingLoadFutureArray(adjRenderSourceLoadRefFutures);
					this.adjacentLoadRefFutures = null;
					
					LOGGER.error("Unexpected error in LodRenderSection loading, Error: "+e.getMessage(), e);
					this.buildAndUploadRenderDataToGpuFuture = null;
					this.bufferBuildFuture = null;
				}
			});
		}, executor);
	}
	/** Should be called on the {@link ThreadPoolUtil#getFileHandlerExecutor()} */
	private ReferencedFutureWrapper[] getNeighborRenderSourcesAsync()
	{
		ReferencedFutureWrapper[] futureArray = new ReferencedFutureWrapper[EDhDirection.ADJ_DIRECTIONS.length];
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
					futureArray[arrayIndex] = adjRenderSection.getRenderSourceAsync();
				}
			}
			catch (IndexOutOfBoundsException ignore) {}
			
			if (futureArray[arrayIndex] == null)
			{
				futureArray[arrayIndex] = new ReferencedFutureWrapper(CompletableFuture.completedFuture(null));
			}
		}
		
		this.adjacentLoadRefFutures = futureArray;
		return futureArray;
	}
	/** Will try to return the same {@link CompletableFuture} if multiple requests are made for the same position */
	private ReferencedFutureWrapper getRenderSourceAsync()
	{
		try
		{
			this.getRenderSourceLock.lock();
			
			
			// if a load is already in progress, use that existing one
			// (this reduces the number of duplicate loads that may happen when initially loading the world)
			if (this.renderSourceLoadingRefFuture != null)
			{
				// increment the number of objects needing this future
				this.renderSourceLoadingRefFuture.incrementRefCount();
				return this.renderSourceLoadingRefFuture;
			}
			
			
			
			ThreadPoolExecutor executor = ThreadPoolUtil.getFileHandlerExecutor();
			if (executor == null || executor.isTerminated())
			{
				return new ReferencedFutureWrapper(CompletableFuture.completedFuture(null));
			}
			
			this.renderSourceLoadingRefFuture = new ReferencedFutureWrapper(CompletableFuture.supplyAsync(() ->
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
			}, executor));
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
		boolean adjacentIsSameDetailLevel = (detailLevel == DhSectionPos.getDetailLevel(this.pos));
		return adjacentIsSameDetailLevel;
	}
	
	
	/** 
	 * Note: can cause issues with neighboring LOD sections 
	 * if only some (vs all) futures are canceled.
	 */
	public void cancelGpuUpload()
	{
		CompletableFuture<Void> future = this.buildAndUploadRenderDataToGpuFuture;
		this.buildAndUploadRenderDataToGpuFuture = null;
		this.bufferBuildFuture = null;
		if (future != null)
		{
			// interrupting the future speeds things up, but also causes
			// some LODs to never load in properly
			future.cancel(false);
		}
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
	
	public boolean isFullyGenerated() { return this.missingPositionsCalculated && this.missingGenerationPos.size() == 0; }
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
	
	
	
	//=========//
	// cleanup //
	//=========//
	
	/** does nothing if the passed in value is null. */
	private void tryDecrementingLoadFutureArray(@Nullable ReferencedFutureWrapper[] refFutures)
	{
		if (refFutures != null)
		{
			for (ReferencedFutureWrapper futureWrapper : refFutures)
			{
				if (futureWrapper != null)
				{
					futureWrapper.decrementRefCount();
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
		
		// this render section won't be rendering, we don't need to load any data for it
		this.tryDecrementingLoadFutureArray(this.adjacentLoadRefFutures);
		if (this.renderSourceLoadingRefFuture != null)
		{
			this.renderSourceLoadingRefFuture.decrementRefCount();
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
	
	/**
	 * Used to keep track of whether a {@link ColumnRenderSource} {@link CompletableFuture}
	 * is in use or not, and if not in use cancels the future. <br> <br>
	 *  
	 * This helps speed up LOD loading by canceling loads that are no longer needed,
	 * IE out of range or in an unloaded dimension.
	 */
	private static class ReferencedFutureWrapper
	{
		public final CompletableFuture<ColumnRenderSource> future;
		// starts at 1 since the constructing method is referencing this future
		private final AtomicInteger refCount = new AtomicInteger(1);
		
		
		
		public ReferencedFutureWrapper(CompletableFuture<ColumnRenderSource> future) { this.future = future; }
		
		public void incrementRefCount() { this.refCount.incrementAndGet(); }
		public void decrementRefCount()
		{
			// automatically clean up this future if no one else is referencing it
			if (this.refCount.decrementAndGet() <= 0)
			{
				if (this.future != null)
				{
					if (!this.future.isDone())
					{
						this.future.cancel(true);
					}
				}
			}
		}
		
		
		
		@Override 
		public String toString() { return this.future.toString() + " - " + this.refCount.get(); }
		
	}
	
}
