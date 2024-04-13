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
import com.seibel.distanthorizons.core.dataObjects.transformers.FullDataToRenderDataTransformer;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.glObject.GLProxy;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.ColumnRenderBuffer;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import org.apache.logging.log4j.Logger;

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
	
	
	
	public final DhSectionPos pos;
	
	private final IDhClientLevel level;
	@WillNotClose
	private final FullDataSourceProviderV2 fullDataSourceProvider;
	private final LodQuadTree quadTree;
	
	
	public boolean renderingEnabled = false;
	private boolean canRender = false;
	
	/** this reference is necessary so we can determine what VBO to render */
	public ColumnRenderBuffer renderBuffer; 
	
	
	/** 
	 * Encapsulates everything between pulling data from the database (including neighbors)
	 * up to the point when geometry data is uploaded to the GPU.
	 */
	private CompletableFuture<Void> uploadRenderDataToGpuFuture = null;
	
	private final ReentrantLock getRenderSourceLock = new ReentrantLock();
	/** Used to track this position's render data loading */
	private CompletableFuture<ColumnRenderSource> renderSourceLoadingFuture = null;
	
	private boolean missingPositionsCalculated = false;
	/** should be an empty array if no positions need to be generated */
	private ArrayList<DhSectionPos> missingGenerationPos = null;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public LodRenderSection(DhSectionPos pos, LodQuadTree quadTree, IDhClientLevel level, FullDataSourceProviderV2 fullDataSourceProvider)
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
	
	public void uploadRenderDataToGpuAsync()
	{
		if (!GLProxy.hasInstance())
		{
			// it's possible to try uploading buffers before the GLProxy has been initialized
			// which would cause the system to crash
			return;
		}
		
		if (this.uploadRenderDataToGpuFuture != null)
		{
			// don't accidentally queue multiple uploads at the same time
			return;
		}
		
		
		
		ThreadPoolExecutor executor = ThreadPoolUtil.getFileHandlerExecutor();
		if (executor == null || executor.isTerminated())
		{
			return;
		}
		
		this.uploadRenderDataToGpuFuture = CompletableFuture.runAsync(() -> 
		{
			this.getRenderSourceAsync().thenAccept((renderSource) ->
			{
				try
				{
					if (renderSource == null || renderSource.isEmpty())
					{
						// nothing needs to be rendered
						this.canRender = false;
						return;
					}
					
					
					
					//=================================//
					// get the neighbor render sources //
					//=================================//
					
					CompletableFuture<ColumnRenderSource>[] adjacentLoadFutures = this.getNeighborRenderSourcesAsync();
					
					
					
					//==============================//
					// build/upload new render data //
					//==============================//
					
					CompletableFuture.allOf(adjacentLoadFutures).thenRun(() ->
					{
						try
						{
							ColumnRenderBuffer previousBuffer = this.renderBuffer;
							
							ColumnRenderSource[] adjacentRenderSections = new ColumnRenderSource[EDhDirection.ADJ_DIRECTIONS.length];
							for (int i = 0; i < EDhDirection.ADJ_DIRECTIONS.length; i++)
							{
								adjacentRenderSections[i] = adjacentLoadFutures[i].getNow(null);
							}
							ColumnRenderBufferBuilder.buildAndUploadBuffersAsync(this.level, renderSource, adjacentRenderSections).thenAccept((buffer) ->
							{
								// upload complete, clean up the old data if 
								this.renderBuffer = buffer;
								this.canRender = true;
								this.uploadRenderDataToGpuFuture = null;
								
							});
						}
						catch (Exception e)
						{
							LOGGER.error("Unexpected error in LodRenderSection loading, Error: "+e.getMessage(), e);
							this.uploadRenderDataToGpuFuture = null;
						}
					});
				}
				catch (Exception e)
				{
					LOGGER.error("Unexpected error in LodRenderSection loading, Error: "+e.getMessage(), e);
					this.uploadRenderDataToGpuFuture = null;
				}
			});
		}, executor);
	}
	/** Should be called on the {@link ThreadPoolUtil#getFileHandlerExecutor()} */
	@SuppressWarnings("unchecked") // creating an array of CompletableFuture's is unchecked, unfortunately I don't currently see a better fix
	private CompletableFuture<ColumnRenderSource>[] getNeighborRenderSourcesAsync()
	{
		CompletableFuture<ColumnRenderSource>[] futureArray = new CompletableFuture[EDhDirection.ADJ_DIRECTIONS.length];
		for (int i = 0; i < EDhDirection.ADJ_DIRECTIONS.length; i++)
		{
			EDhDirection direction = EDhDirection.ADJ_DIRECTIONS[i];
			int arrayIndex = direction.ordinal() - 2;
			
			DhSectionPos adjPos = this.pos.getAdjacentPos(direction);
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
				futureArray[arrayIndex] = CompletableFuture.completedFuture(null);
			}
		}
		
		return futureArray;
	}
	/** Will try to return the same {@link CompletableFuture} if multiple requests are made for the same position */
	private CompletableFuture<ColumnRenderSource> getRenderSourceAsync()
	{
		try
		{
			this.getRenderSourceLock.lock();
			
			
			// if a load is already in progress, use that existing one
			// (this reduces the number of duplicate loads that may happen when initially loading the world)
			if (this.renderSourceLoadingFuture != null)
			{
				return this.renderSourceLoadingFuture;
			}
			
			
			
			ThreadPoolExecutor executor = ThreadPoolUtil.getFileHandlerExecutor();
			if (executor == null || executor.isTerminated())
			{
				return CompletableFuture.completedFuture(null);
			}
			
			this.renderSourceLoadingFuture = CompletableFuture.supplyAsync(() ->
			{
				try (FullDataSourceV2 fullDataSource = this.fullDataSourceProvider.get(this.pos))
				{
					ColumnRenderSource renderSource = FullDataToRenderDataTransformer.transformFullDataToRenderSource(fullDataSource, this.level);
					this.renderSourceLoadingFuture = null;
					return renderSource;
				}
				catch (Exception e)
				{
					LOGGER.warn("Unable to get render source " + this.pos + ", error: " + e.getMessage(), e);
					this.renderSourceLoadingFuture = null;
					return null;
				}
			}, executor);
			return this.renderSourceLoadingFuture;
		}
		finally
		{
			this.getRenderSourceLock.unlock();
		}
	}
	
	
	
	//========================//
	// getters and properties //
	//========================//
	
	public boolean canRender() { return this.canRender; }
	
	public boolean gpuUploadInProgress() { return this.uploadRenderDataToGpuFuture != null; }
	
	
	
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
					
					DhSectionPos pos = this.missingGenerationPos.remove(i);
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
		return "LodRenderSection{" +
				"pos=" + this.pos +
				'}';
	}
	
	@Override
	public void close()
	{
		DebugRenderer.unregister(this, Config.Client.Advanced.Debugging.DebugWireframe.showRenderSectionStatus);
		
		if (this.renderBuffer != null)
		{
			this.renderBuffer.close();
		}
		
		if (this.uploadRenderDataToGpuFuture != null)
		{
			this.uploadRenderDataToGpuFuture.cancel(true);
		}
		
		if (this.renderSourceLoadingFuture != null)
		{
			this.renderSourceLoadingFuture.cancel(true);
		}
		
		
		// remove any active world gen requests that may be for this position
		ThreadPoolExecutor executor = ThreadPoolUtil.getCleanupExecutor();
		if (executor != null && !executor.isTerminated())
		{
			// while this should generally be a fast operation 
			// this is run on a separate thread to prevent lag on the render thread
			
			try
			{
				executor.execute(() -> this.fullDataSourceProvider.removeRetrievalRequestIf((genPos) -> this.pos.contains(genPos)));
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
		else if (this.uploadRenderDataToGpuFuture != null)
		{
			color = Color.yellow;
		}
		else if (this.canRender)
		{
			color = Color.cyan;
		}
		
		debugRenderer.renderBox(new DebugRenderer.Box(this.pos, 400, 8f, Objects.hashCode(this), 0.1f, color));
	}
	
}
