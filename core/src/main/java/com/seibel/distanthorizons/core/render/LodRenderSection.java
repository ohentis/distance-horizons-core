/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
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

import com.google.common.base.Suppliers;
import com.google.common.cache.Cache;
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
import com.seibel.distanthorizons.core.util.KeyedLockContainer;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.annotation.WillNotClose;
import java.awt.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A render section represents an area that could be rendered.
 * For more information see {@link LodQuadTree}.
 */
public class LodRenderSection implements IDebugRenderable, AutoCloseable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	
	/**
	 * Used to limit how many upload tasks are queued at once.
	 * If all the upload tasks are queued at once, they will start uploading nearest
	 * to the player, however if the player moves, that order is no longer valid and holes may appear
	 * as further sections are loaded before closer ones.
	 * Only queuing a few of the sections at a time solves this problem.
	 */
	public static final AtomicInteger GLOBAL_UPLOAD_TASKS_COUNT_REF = new AtomicInteger(0);
	
	
	
	public final long pos;
	
	private final IDhClientLevel level;
	@WillNotClose
	private final FullDataSourceProviderV2 fullDataSourceProvider;
	private final LodQuadTree quadTree;
	private final KeyedLockContainer<Long> renderLoadLockContainer;
	private final Cache<Long, ColumnRenderSource> cachedRenderSourceByPos;
	
	
	
	private boolean renderingEnabled = false;
	
	/** this reference is necessary so we can determine what VBO to render */
	public ColumnRenderBuffer renderBuffer; 
	
	
	/** 
	 * Encapsulates everything between pulling data from the database (including neighbors)
	 * up to the point when geometry data is uploaded to the GPU.
	 */
	private CompletableFuture<Void> getAndBuildRenderDataFuture = null;
	@Nullable
	public CompletableFuture<Void> getRenderDataBuildFuture() { return this.getAndBuildRenderDataFuture; } 
	
	/** 
	 * used alongside {@link LodRenderSection#getAndBuildRenderDataFuture} so we can remove
	 * unnecessary tasks from the executor.
	 */
	private Runnable getAndBuildRenderDataRunnable = null;
	
	/** 
	 * Represents just uploading the {@link LodQuadBuilder} to the GPU. <br>
	 * Separate from {@link LodRenderSection#getAndBuildRenderDataFuture} because they run on
	 * different threads (buffer uploading is on the MC render thread) and need to be canceled separately.
	 */
	private CompletableFuture<ColumnRenderBuffer> bufferUploadFuture = null;

	/** should be an empty array if no positions need to be generated */
	@Nullable
	private Supplier<LongArrayList> missingGenerationPos;
	private LongArrayList getMissingGenerationPos() { return this.missingGenerationPos != null ? this.missingGenerationPos.get() : null; }
	
	private boolean checkedIfFullDataSourceExists = false;
	private boolean fullDataSourceExists = false;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public LodRenderSection(
			long pos, 
			LodQuadTree quadTree, 
			IDhClientLevel level, FullDataSourceProviderV2 fullDataSourceProvider, 
			Cache<Long, ColumnRenderSource> cachedRenderSourceByPos, KeyedLockContainer<Long> renderLoadLockContainer)
	{
		this.pos = pos;
		this.quadTree = quadTree;
		this.cachedRenderSourceByPos = cachedRenderSourceByPos;
		this.renderLoadLockContainer = renderLoadLockContainer;
		this.level = level;
		this.fullDataSourceProvider = fullDataSourceProvider;
		
		DebugRenderer.register(this, Config.Client.Advanced.Debugging.DebugWireframe.showRenderSectionStatus);
	}
	
	
	
	//======================================//
	// render data generation and uploading //
	//======================================//
	
	/** @return true if the upload started, false if it wasn't able to for any reason */
	public synchronized boolean uploadRenderDataToGpuAsync()
	{
		if (!GLProxy.hasInstance())
		{
			// it's possible to try uploading buffers before the GLProxy has been initialized
			// which would cause the system to crash
			return false;
		}
		
		if (this.getAndBuildRenderDataFuture != null)
		{
			// don't accidentally queue multiple uploads at the same time
			return false;
		}
		
		PriorityTaskPicker.Executor executor = ThreadPoolUtil.getFileHandlerExecutor();
		if (executor == null || executor.isTerminated())
		{
			return false;
		}
		
		// Only queue a some of the upload tasks at a time,
		// this means the closer (higher priority) tasks will load first.
		// This also prevents issues where the nearby tasks are canceled due to
		// LOD detail level changing, and having holes in the world
		if (GLOBAL_UPLOAD_TASKS_COUNT_REF.getAndIncrement() > executor.getPoolSize())
		{
			GLOBAL_UPLOAD_TASKS_COUNT_REF.decrementAndGet();
			return false;
		}
		
		try
		{
			CompletableFuture<Void> future = new CompletableFuture<>();
			this.getAndBuildRenderDataFuture = future;
			future.handle((voidObj, throwable) -> 
			{
				// this has to fire are the end of every added future, otherwise we'll lock up and nothing will load
				GLOBAL_UPLOAD_TASKS_COUNT_REF.decrementAndGet(); 
				return null; 
			});
			
			this.getAndBuildRenderDataRunnable = () ->
			{
				this.getAndUploadRenderDataToGpu();
				
				// the future is passed in separate to prevent any possible race condition null pointers
				future.complete(null);
				// the task is done, we don't need to track these anymore
				this.getAndBuildRenderDataFuture = null;
				this.getAndBuildRenderDataRunnable = null;
			};
			executor.execute(this.getAndBuildRenderDataRunnable);
			
			return true;
		}
		catch (RejectedExecutionException ignore)
		{
			this.getAndBuildRenderDataFuture.complete(null);
			this.getAndBuildRenderDataFuture = null;
			this.getAndBuildRenderDataRunnable = null;
			
			/* the thread pool was probably shut down because it's size is being changed, just wait a sec and it should be back */
			return false;
		}
	}
	private void getAndUploadRenderDataToGpu()
	{
		try
		{
			ColumnRenderSource renderSource = this.getRenderSourceForPos(this.pos);
			if (renderSource == null)
			{
				// nothing needs to be rendered
				// TODO how doesn't this cause infinite file handler loops?
				//  to trigger an upload we check if the buffer is null, and we aren't
				//  setting the render buffer here
				return;
			}
			
			
			boolean enableTransparency = Config.Client.Advanced.Graphics.Quality.transparency.get().transparencyEnabled;
			LodQuadBuilder lodQuadBuilder = new LodQuadBuilder(enableTransparency, this.level.getClientLevelWrapper());
			
			// load adjacent render sources
			{
				ColumnRenderSource northRenderSource = this.getRenderSourceForPos(DhSectionPos.getAdjacentPos(this.pos, EDhDirection.NORTH));
				ColumnRenderSource southRenderSource = this.getRenderSourceForPos(DhSectionPos.getAdjacentPos(this.pos, EDhDirection.SOUTH));
				ColumnRenderSource eastRenderSource = this.getRenderSourceForPos(DhSectionPos.getAdjacentPos(this.pos, EDhDirection.EAST));
				ColumnRenderSource westRenderSource = this.getRenderSourceForPos(DhSectionPos.getAdjacentPos(this.pos, EDhDirection.WEST));
				
				ColumnRenderSource[] adjacentRenderSections = new ColumnRenderSource[EDhDirection.ADJ_DIRECTIONS.length];
				adjacentRenderSections[EDhDirection.NORTH.ordinal() - 2] = northRenderSource;
				adjacentRenderSections[EDhDirection.SOUTH.ordinal() - 2] = southRenderSource;
				adjacentRenderSections[EDhDirection.EAST.ordinal() - 2] = eastRenderSource;
				adjacentRenderSections[EDhDirection.WEST.ordinal() - 2] = westRenderSource;
				
				boolean[] adjIsSameDetailLevel = new boolean[EDhDirection.ADJ_DIRECTIONS.length];
				adjIsSameDetailLevel[EDhDirection.NORTH.ordinal() - 2] = this.isAdjacentPosSameDetailLevel(EDhDirection.NORTH);
				adjIsSameDetailLevel[EDhDirection.SOUTH.ordinal() - 2] = this.isAdjacentPosSameDetailLevel(EDhDirection.SOUTH);
				adjIsSameDetailLevel[EDhDirection.EAST.ordinal() - 2] = this.isAdjacentPosSameDetailLevel(EDhDirection.EAST);
				adjIsSameDetailLevel[EDhDirection.WEST.ordinal() - 2] = this.isAdjacentPosSameDetailLevel(EDhDirection.WEST);
				
				// the render sources are only needed in this synchronous method,
				// then they can be closed
				ColumnRenderBufferBuilder.makeLodRenderData(lodQuadBuilder, renderSource, this.level, adjacentRenderSections, adjIsSameDetailLevel);
			}
			
			this.uploadToGpuAsync(lodQuadBuilder);
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected error while loading LodRenderSection ["+DhSectionPos.toString(this.pos)+"], Error: [" + e.getMessage() + "].", e);
		}
	}
	@Nullable
	private ColumnRenderSource getRenderSourceForPos(long pos) 
	{
		ReentrantLock lock = this.renderLoadLockContainer.getLockForPos(pos);
		try
		{
			// we don't want multiple threads attempting to load the same position at the same time
			lock.lock();
			
			// use the cached data if possible
			ColumnRenderSource renderSource = this.cachedRenderSourceByPos.getIfPresent(pos);
			if (renderSource != null)
			{
				return renderSource;
			}
			
			// generate new render source
			try (FullDataSourceV2 fullDataSource = this.fullDataSourceProvider.get(pos))
			{
				renderSource = FullDataToRenderDataTransformer.transformFullDataToRenderSource(fullDataSource, this.level);
				// only add valid data to the cache (to prevent null pointers)
				if (renderSource != null)
				{
					this.cachedRenderSourceByPos.put(pos, renderSource);
				}
			}
			
			return renderSource;
		}
		finally
		{
			lock.unlock();
		}
	}
	private boolean isAdjacentPosSameDetailLevel(EDhDirection direction)
	{
		long adjPos = DhSectionPos.getAdjacentPos(this.pos, direction);
		byte detailLevel = this.quadTree.calculateExpectedDetailLevel(new DhBlockPos2D(MC.getPlayerBlockPos()), adjPos);
		detailLevel += DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
		return detailLevel == DhSectionPos.getDetailLevel(this.pos);
	}
	private void uploadToGpuAsync(LodQuadBuilder lodQuadBuilder)
	{
		if (this.bufferUploadFuture != null)
		{
			// shouldn't normally happen, but just in case canceling the previous future
			// prevents the CPU from working on something that won't be used
			this.bufferUploadFuture.cancel(true);
		}
		
		this.bufferUploadFuture = ColumnRenderBufferBuilder.uploadBuffersAsync(this.level, this.pos, lodQuadBuilder);
		this.bufferUploadFuture.thenAccept((buffer) ->
		{
			// needed to clean up the old data
			ColumnRenderBuffer previousBuffer = this.renderBuffer;
			
			// upload complete
			this.renderBuffer = buffer.buffersUploaded ? buffer : null;
			this.getAndBuildRenderDataFuture = null;
			
			if (previousBuffer != null)
			{
				previousBuffer.close();
			}
		});
	}
	
	
	
	//========================//
	// getters and properties //
	//========================//
	
	public boolean canRender() { return this.renderBuffer != null; }
	
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
	
	
	public boolean gpuUploadInProgress() { return this.getAndBuildRenderDataFuture != null; }
	
	
	
	//=================================//
	// full data retrieval (world gen) //
	//=================================//
	
	public boolean isFullyGenerated()
	{
		LongArrayList missingGenerationPos = this.getMissingGenerationPos();
		return missingGenerationPos != null && missingGenerationPos.isEmpty();
	}
	/** Returns true if an LOD exists, regardless of what data is in it */
	public boolean getFullDataSourceExists() 
	{  
		if (!this.checkedIfFullDataSourceExists)
		{
			this.fullDataSourceExists = this.fullDataSourceProvider.repo.existsWithKey(this.pos);
			this.checkedIfFullDataSourceExists = true;
		}
		
		return this.fullDataSourceExists;
	}
	public void updateFullDataSourceExists() 
	{
		// we don't have any ability to remove LODs so we only
		// need to check if an LOD was previously missing
		if (!this.fullDataSourceExists)
		{
			this.checkedIfFullDataSourceExists = false;
			this.getFullDataSourceExists();
		}
	}
	
	public boolean missingPositionsCalculated() { return this.getMissingGenerationPos() != null; }
	public int ungeneratedPositionCount()
	{
		LongArrayList missingGenerationPos = this.getMissingGenerationPos();
		return missingGenerationPos != null ? missingGenerationPos.size() : 0;
	}
	public int ungeneratedChunkCount()
	{
		LongArrayList missingGenerationPos = this.getMissingGenerationPos();
		if (missingGenerationPos == null)
		{
			return 0;
		}
		
		int chunkCount = 0;
		// get the number of chunks each position contains
		for (int i = 0; i < missingGenerationPos.size(); i++)
		{
			int chunkWidth = DhSectionPos.getChunkWidth(missingGenerationPos.getLong(i));
			chunkCount += (chunkWidth * chunkWidth);
		}
		return chunkCount;
	}
	
	public void tryQueuingMissingLodRetrieval()
	{
		if (this.fullDataSourceProvider.canRetrieveMissingDataSources() && this.fullDataSourceProvider.canQueueRetrieval())
		{
			// calculate the missing positions if not already done
			if (this.missingGenerationPos == null)
			{
				//this.missingGenerationPos = Suppliers.memoize(() -> this.fullDataSourceProvider.getPositionsToRetrieve(this.pos));
				this.missingGenerationPos = Suppliers.memoizeWithExpiration(() -> this.fullDataSourceProvider.getPositionsToRetrieve(this.pos), 1, TimeUnit.MINUTES);
			}
			
			LongArrayList missingGenerationPos = this.getMissingGenerationPos();
			if (missingGenerationPos != null)
			{
				// queue from last to first to prevent shifting the array unnecessarily
				for (int i = missingGenerationPos.size() - 1; i >= 0; i--)
				{
					if (!this.fullDataSourceProvider.canQueueRetrieval())
					{
						// the data source provider isn't accepting any more jobs
						break;
					}
					
					long pos = missingGenerationPos.removeLong(i);
					boolean positionQueued = (this.fullDataSourceProvider.queuePositionForRetrieval(pos) != null);
					if (!positionQueued)
					{
						// shouldn't normally happen, but just in case
						missingGenerationPos.add(pos);
					}
				}
			}
		}
	}
	
	
	
	//==============//
	// base methods //
	//==============//
	
	@Override
	public void debugRender(DebugRenderer debugRenderer)
	{
		Color color = Color.red;
		if (this.renderingEnabled)
		{
			color = Color.green;
		}
		else if (this.getAndBuildRenderDataFuture != null)
		{
			color = Color.yellow;
		}
		else if (this.canRender())
		{
			color = Color.cyan;
		}
		
		debugRenderer.renderBox(new DebugRenderer.Box(this.pos, 400, 8f, Objects.hashCode(this), 0.1f, color));
	}
	
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
		
		// removes any in-progress futures since they aren't needed any more
		CompletableFuture<Void> buildFuture = this.getAndBuildRenderDataFuture;
		if (buildFuture != null)
		{
			// remove the task from our executor if present
			// note: don't cancel the task since that prevents cleanup, we just don't want it to run
			PriorityTaskPicker.Executor executor = ThreadPoolUtil.getFileHandlerExecutor();
			if (executor != null && !executor.isTerminated())
			{
				Runnable runnable = this.getAndBuildRenderDataRunnable;
				if (runnable != null)
				{
					executor.remove(runnable);
				}
			}
		}
		
		CompletableFuture<ColumnRenderBuffer> uploadFuture = this.bufferUploadFuture;
		if (uploadFuture != null)
		{
			uploadFuture.cancel(true);
		}
		
		
		
		// remove any active world gen requests that may be for this position
		ThreadPoolExecutor executor = ThreadPoolUtil.getCleanupExecutor();
		// while this should generally be a fast operation 
		// this is run on a separate thread to prevent lag on the render thread
		executor.execute(() -> this.fullDataSourceProvider.removeRetrievalRequestIf((genPos) -> DhSectionPos.contains(this.pos, genPos)));
	}
	
	
	
}
