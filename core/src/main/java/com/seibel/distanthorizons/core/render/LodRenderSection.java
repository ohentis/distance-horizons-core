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

import java.awt.*;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * A render section represents an area that could be rendered.
 * For more information see {@link LodQuadTree}.
 */
public class LodRenderSection implements IDebugRenderable, AutoCloseable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	
	public final DhSectionPos pos;
	
	private final IDhClientLevel level;
	private final FullDataSourceProviderV2 fullDataSourceProvider;
	
	
	public boolean renderingEnabled = false;
	
	/** this reference is necessary so we can determine what VBO to render */
	public ColumnRenderBuffer renderBuffer; 
	
	
	private CompletableFuture<Void> renderSourceLoadingFuture = null;
	private boolean canRender = false;
	
	private boolean missingPositionsCalculated = false;
	/** should be an empty array if no positions need to be generated */
	private ArrayList<DhSectionPos> missingGenerationPos = null;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public LodRenderSection(DhSectionPos pos, IDhClientLevel level, FullDataSourceProviderV2 fullDataSourceProvider)
	{
		this.pos = pos;
		this.level = level;
		this.fullDataSourceProvider = fullDataSourceProvider;
		
		DebugRenderer.register(this, Config.Client.Advanced.Debugging.DebugWireframe.showRenderSectionStatus);
	}
	
	
	
	//=============//
	// render data //
	//=============//
	
	public void loadRenderSourceAsync()
	{
		if (!GLProxy.hasInstance())
		{
			// it's possible to try uploading buffers before the GLProxy has been initialized
			// which would cause the system to crash
			return;
		}
		
		if (this.renderSourceLoadingFuture != null)
		{
			return;
		}
		
		
		
		// run on the file handler pool since a number of operations
		// require a number of database hits
		ThreadPoolExecutor executor = ThreadPoolUtil.getFileHandlerExecutor();
		if (executor == null || executor.isTerminated())
		{
			return;
		}
		
		
		this.renderSourceLoadingFuture = CompletableFuture.runAsync(() -> 
		{
			FullDataSourceV2 fullDataSource = null;
			ColumnRenderSource[] adjacentRenderSections = null;
			ColumnRenderSource renderSource = null;
			
			try
			{
				// get this positions data source
				fullDataSource = this.fullDataSourceProvider.get(this.pos);
				renderSource = FullDataToRenderDataTransformer.transformFullDataToRenderSource(fullDataSource, this.level);
				if (renderSource.isEmpty())
				{
					// nothing needs to be rendered
					this.canRender = false;
					return;
				}
				
				
				adjacentRenderSections = this.getAndCreateNeighborRenderSources();
				
				ColumnRenderBuffer previousBuffer = this.renderBuffer;
				
				CompletableFuture<ColumnRenderBuffer> uploadFuture = ColumnRenderBufferBuilder.buildAndUploadBuffersAsync(this.level, renderSource, adjacentRenderSections);
				this.renderBuffer = uploadFuture.join();
				
				if (previousBuffer != null)
				{
					previousBuffer.close();
				}
				
				this.canRender = true;
			}
			catch (Exception e)
			{
				LOGGER.error("Unexpected error in LodRenderSection loading, Error: "+e.getMessage(), e);
			}
			finally
			{
				// clean up pooled data sources
				try
				{
					if (fullDataSource != null)
					{
						fullDataSource.close();
					}
					
					if (renderSource != null)
					{
						renderSource.close();
					}
					
					if (adjacentRenderSections != null)
					{
						for (int i = 0; i < adjacentRenderSections.length; i++)
						{
							ColumnRenderSource adjacentRenderSource = adjacentRenderSections[i];
							if (adjacentRenderSource != null)
							{
								adjacentRenderSource.close();
							}
						}
					}
				}
				catch (Exception ignore){ }
				
				this.renderSourceLoadingFuture = null;
			}
		}, executor);
	}
	/** Should be called on the {@link ThreadPoolUtil#getFileHandlerExecutor()} */
	private ColumnRenderSource[] getAndCreateNeighborRenderSources()
	{
		ColumnRenderSource[] adjacentRenderSections = new ColumnRenderSource[EDhDirection.ADJ_DIRECTIONS.length];
		for (EDhDirection direction : EDhDirection.ADJ_DIRECTIONS)
		{
			DhSectionPos adjPos = this.pos.getAdjacentPos(direction);
			try (FullDataSourceV2 fullDataSource = this.fullDataSourceProvider.get(adjPos))
			{
				// TODO some temporary caching could be done here 
				ColumnRenderSource renderSource = FullDataToRenderDataTransformer.transformFullDataToRenderSource(fullDataSource, this.level);
				adjacentRenderSections[direction.ordinal() - 2] = renderSource;
			}
			catch (Exception e)
			{
				LOGGER.warn("Unable to get neighbor render source "+this.pos+" - "+adjPos+", error: "+e.getMessage(), e);
			}
		}
		
		return adjacentRenderSections;
	}
	
	
	
	//========================//
	// getters and properties //
	//========================//
	
	public boolean canRender() { return this.canRender; }
	
	public boolean loadingRenderSource() { return this.renderSourceLoadingFuture != null; }
	
	
	
	//=================================//
	// full data retrieval (world gen) //
	//=================================//
	
	public boolean isFullyGenerated() { return this.missingPositionsCalculated && this.missingGenerationPos.size() == 0; }
	public boolean missingPositionsCalculated() { return this.missingPositionsCalculated; }
	public int ungeneratedPositionCount() { return (this.missingGenerationPos != null) ? this.missingGenerationPos.size() : 0; }
	
	public void tryQueuingMissingLodRetrieval(FullDataSourceProviderV2 fullDataSourceProvider)
	{
		if (fullDataSourceProvider.canRetrieveMissingDataSources() && fullDataSourceProvider.canQueueRetrieval())
		{
			// calculate the missing positions if not already done
			if (!this.missingPositionsCalculated)
			{
				this.missingGenerationPos = fullDataSourceProvider.getPositionsToRetrieve(this.pos);
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
					if (!fullDataSourceProvider.canQueueRetrieval())
					{
						// the data source provider isn't accepting any more jobs
						break;
					}
					
					DhSectionPos pos = this.missingGenerationPos.remove(i);
					boolean positionQueued = fullDataSourceProvider.queuePositionForRetrieval(pos);
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
		
		if (this.renderSourceLoadingFuture != null)
		{
			this.renderSourceLoadingFuture.cancel(true);
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
		else if (this.renderSourceLoadingFuture != null)
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
