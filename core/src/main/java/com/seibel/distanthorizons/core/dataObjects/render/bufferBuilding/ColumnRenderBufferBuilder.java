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

package com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiDebugRendering;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.glObject.GLProxy;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.core.util.ColorUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.util.objects.UncheckedInterruptedException;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.ColumnArrayView;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Used to populate the buffers in a {@link ColumnRenderSource} object.
 *
 * @see ColumnRenderSource
 */
public class ColumnRenderBufferBuilder
{
	public static final ConfigBasedLogger EVENT_LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logRendererBufferEvent.get());
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	
	
	//==============//
	// vbo building //
	//==============//
	
	public static CompletableFuture<ColumnRenderBuffer> buildAndUploadBuffersAsync(
			IDhClientLevel clientLevel,
			ColumnRenderSource renderSource, ColumnRenderSource[] adjData)
	{
		ThreadPoolExecutor bufferBuilderExecutor = ThreadPoolUtil.getBufferBuilderExecutor();
		ThreadPoolExecutor bufferUploaderExecutor = ThreadPoolUtil.getBufferUploaderExecutor();
		if ((bufferBuilderExecutor == null || bufferBuilderExecutor.isTerminated()) ||
			(bufferUploaderExecutor == null || bufferUploaderExecutor.isTerminated()))
		{
			// one or more of the thread pools has been shut down
			CompletableFuture<ColumnRenderBuffer> future = new CompletableFuture<>();
			future.cancel(true);
			return future;
		}
		
		try
		{
			return CompletableFuture.supplyAsync(() ->
				{
					try
					{
						boolean enableTransparency = Config.Client.Advanced.Graphics.Quality.transparency.get().transparencyEnabled;
						
						long builderStartTime = System.currentTimeMillis();
						
						LodQuadBuilder builder = new LodQuadBuilder(enableTransparency, clientLevel.getClientLevelWrapper());
						makeLodRenderData(builder, renderSource, adjData);
						
						long builderEndTime = System.currentTimeMillis();
						long buildMs = builderEndTime - builderStartTime;
						//LOGGER.debug("RenderRegion end QuadBuild @ " + renderSource.pos + " took: " + buildMs);
						
						return builder;
					}
					catch (UncheckedInterruptedException e)
					{
						throw e;
					}
					catch (Throwable e3)
					{
						LOGGER.error("\"LodNodeBufferBuilder\" was unable to build quads: ", e3);
						throw e3;
					}
				}, bufferBuilderExecutor)
				.thenApplyAsync((quadBuilder) ->
				{
					try
					{
						ColumnRenderBuffer buffer = new ColumnRenderBuffer(new DhBlockPos(DhSectionPos.getMinCornerBlockX(renderSource.pos), clientLevel.getMinY(), DhSectionPos.getMinCornerBlockZ(renderSource.pos)));
						try
						{
							buffer.uploadBuffer(quadBuilder, GLProxy.getInstance().getGpuUploadMethod());
							LodUtil.assertTrue(buffer.buffersUploaded);
							return buffer;
						}
						catch (Exception e)
						{
							buffer.close();
							throw e;
						}
					}
					catch (InterruptedException e)
					{
						throw UncheckedInterruptedException.convert(e);
					}
					catch (Throwable e3)
					{
						LOGGER.error("LodNodeBufferBuilder was unable to upload buffer: " + e3.getMessage(), e3);
						throw e3;
					}
				}, bufferUploaderExecutor);
		}
		catch (RejectedExecutionException ignore) 
		{
			// the thread pool was probably shut down because it's size is being changed, just wait a sec and it should be back
			
			CompletableFuture<ColumnRenderBuffer> future = new CompletableFuture<>();
			future.cancel(true);
			return future;
		}
	}
	private static void makeLodRenderData(LodQuadBuilder quadBuilder, ColumnRenderSource renderSource, ColumnRenderSource[] adjRegions)
	{
		// Variable initialization
		EDhApiDebugRendering debugMode = Config.Client.Advanced.Debugging.debugRendering.get();
		
		// can be used to limit which section positions are build and thus, rendered
		// useful when debugging a specific section
		boolean enableColumnBufferLimit = Config.Client.Advanced.Debugging.columnBuilderDebugEnable.get();
		if (enableColumnBufferLimit)
		{
			if (DhSectionPos.getDetailLevel(renderSource.pos) == Config.Client.Advanced.Debugging.columnBuilderDebugDetailLevel.get()
				&& DhSectionPos.getX(renderSource.pos) == Config.Client.Advanced.Debugging.columnBuilderDebugXPos.get()
				&& DhSectionPos.getZ(renderSource.pos) == Config.Client.Advanced.Debugging.columnBuilderDebugZPos.get())
			{
				int test = 0;
			}
			else
			{
				return;
			}
		}
		
		byte detailLevel = renderSource.getDataDetailLevel();
		for (int x = 0; x < ColumnRenderSource.SECTION_SIZE; x++)
		{
			for (int z = 0; z < ColumnRenderSource.SECTION_SIZE; z++)
			{
				// can be uncommented to limit the buffer building to a specific
				// relative position in this section.
				// useful for debugging a single column's rendering
				if (Config.Client.Advanced.Debugging.columnBuilderDebugEnable.get())
				{
					int wantedX = Config.Client.Advanced.Debugging.columnBuilderDebugXRow.get();
					if (wantedX >= 0 && x != wantedX)
					{
						continue;
					}
					int wantedZ = Config.Client.Advanced.Debugging.columnBuilderDebugZRow.get();
					if (wantedZ >= 0 && z != wantedZ)
					{
						continue;
					}
				}
				
				
				UncheckedInterruptedException.throwIfInterrupted();
				
				ColumnArrayView columnRenderData = renderSource.getVerticalDataPointView(x, z);
				if (columnRenderData.size() == 0
						|| !RenderDataPointUtil.doesDataPointExist(columnRenderData.get(0))
						|| RenderDataPointUtil.isVoid(columnRenderData.get(0)))
				{
					continue;
				}
				
				ColumnRenderSource.DebugSourceFlag debugSourceFlag = renderSource.debugGetFlag(x, z);
				
				ColumnArrayView[][] adjColumnViews = new ColumnArrayView[4][];
				// We extract the adj data in the four cardinal direction
				
				// we first reset the adjShadeDisabled. This is used to disable the shade on the
				// border when we have transparent block like water or glass
				// to avoid having a "darker border" underground
				// Arrays.fill(adjShadeDisabled, false);
				
				
				// We check every adj block in each direction
				
				// If the adj block is rendered in the same region and with same detail
				// and is positioned in a place that is not going to be rendered by vanilla game
				// then we can set this position as adj
				// We avoid cases where the adjPosition is in player chunk while the position is
				// not
				// to always have a wall underwater
				for (EDhDirection lodDirection : EDhDirection.ADJ_DIRECTIONS)
				{
					try
					{
						int xAdj = x + lodDirection.getNormal().x;
						int zAdj = z + lodDirection.getNormal().z;
						boolean isCrossRegionBoundary =
								(xAdj < 0 || xAdj >= ColumnRenderSource.SECTION_SIZE) ||
								(zAdj < 0 || zAdj >= ColumnRenderSource.SECTION_SIZE);
						
						ColumnRenderSource adjRenderSource;
						byte adjDetailLevel;
						
						//we check if the detail of the adjPos is equal to the correct one (region border fix)
						//or if the detail is wrong by 1 value (region+circle border fix)
						if (isCrossRegionBoundary)
						{
							//we compute at which detail that position should be rendered
							adjRenderSource = adjRegions[lodDirection.ordinal() - 2];
							if (adjRenderSource == null)
							{
								continue;
							}
							
							adjDetailLevel = adjRenderSource.getDataDetailLevel();
							if (adjDetailLevel != detailLevel)
							{
								//TODO: Implement this
							}
							else
							{
								if (xAdj < 0)
									xAdj += ColumnRenderSource.SECTION_SIZE;
								
								if (zAdj < 0)
									zAdj += ColumnRenderSource.SECTION_SIZE;
								
								if (xAdj >= ColumnRenderSource.SECTION_SIZE)
									xAdj -= ColumnRenderSource.SECTION_SIZE;
								
								if (zAdj >= ColumnRenderSource.SECTION_SIZE)
									zAdj -= ColumnRenderSource.SECTION_SIZE;
							}
						}
						else
						{
							adjRenderSource = renderSource;
							adjDetailLevel = detailLevel;
						}
						
						if (adjDetailLevel < detailLevel - 1 || adjDetailLevel > detailLevel + 1)
						{
							continue;
						}
						
						if (adjDetailLevel == detailLevel || adjDetailLevel > detailLevel)
						{
							adjColumnViews[lodDirection.ordinal() - 2] = new ColumnArrayView[1];
							adjColumnViews[lodDirection.ordinal() - 2][0] = adjRenderSource.getVerticalDataPointView(xAdj, zAdj);
						}
						else
						{
							adjColumnViews[lodDirection.ordinal() - 2] = new ColumnArrayView[2];
							adjColumnViews[lodDirection.ordinal() - 2][0] = adjRenderSource.getVerticalDataPointView(xAdj, zAdj);
							adjColumnViews[lodDirection.ordinal() - 2][1] = adjRenderSource.getVerticalDataPointView(
									xAdj + (lodDirection.getAxis() == EDhDirection.Axis.X ? 0 : 1),
									zAdj + (lodDirection.getAxis() == EDhDirection.Axis.Z ? 0 : 1));
						}
					}
					catch (RuntimeException e)
					{
						EVENT_LOGGER.warn("Failed to get adj data for [" + detailLevel + ":" + x + "," + z + "] at [" + lodDirection + "], Error: "+e.getMessage(), e);
					}
				} // for adjacent directions
				
				
				// We render every vertical lod present in this position
				// We only stop when we find a block that is void or non-existing block
				for (int i = 0; i < columnRenderData.size(); i++)
				{
					// can be uncommented to limit which vertical LOD is generated
					if (Config.Client.Advanced.Debugging.columnBuilderDebugEnable.get())
					{
						int wantedColumnIndex = Config.Client.Advanced.Debugging.columnBuilderDebugColumnIndex.get();
						if (wantedColumnIndex >= 0 && i != wantedColumnIndex)
						{
							continue;
						}
					}
					
					long data = columnRenderData.get(i);
					// If the data is not render-able (Void or non-existing) we stop since there is
					// no data left in this position
					if (RenderDataPointUtil.isVoid(data) || !RenderDataPointUtil.doesDataPointExist(data))
					{
						break;
					}
					
					long topDataPoint = (i - 1) >= 0 ? columnRenderData.get(i - 1) : RenderDataPointUtil.EMPTY_DATA;
					long bottomDataPoint = (i + 1) < columnRenderData.size() ? columnRenderData.get(i + 1) : RenderDataPointUtil.EMPTY_DATA;
					
					addLodToBuffer(data, topDataPoint, bottomDataPoint, adjColumnViews, detailLevel,
							x, z, quadBuilder, debugMode, debugSourceFlag);
				}
				
			}// for z
		}// for x
		
		quadBuilder.finalizeData();
	}
	private static void addLodToBuffer(
			long data, long topData, long bottomData, ColumnArrayView[][] adjColumnViews,
			byte detailLevel, int offsetPosX, int offsetOosZ, LodQuadBuilder quadBuilder,
			EDhApiDebugRendering debugging, ColumnRenderSource.DebugSourceFlag debugSource)
	{
		DhLodPos blockOffsetPos = new DhLodPos(detailLevel, offsetPosX, offsetOosZ).convertToDetailLevel(LodUtil.BLOCK_DETAIL_LEVEL);
		
		short width = (short) BitShiftUtil.powerOfTwo(detailLevel);
		short x = (short) blockOffsetPos.x;
		short yMin = RenderDataPointUtil.getYMin(data);
		short z = (short) (short) blockOffsetPos.z;
		short ySize = (short) (RenderDataPointUtil.getYMax(data) - yMin);
		
		if (ySize == 0)
		{
			return;
		}
		else if (ySize < 0)
		{
			throw new IllegalArgumentException("Negative y size for the data! Data: " + RenderDataPointUtil.toString(data));
		}
		
		byte blockMaterialId = RenderDataPointUtil.getBlockMaterialId(data);
		
		
		
		int color;
		boolean fullBright = false;
		switch (debugging)
		{
			case OFF:
			{
				float saturationMultiplier = Config.Client.Advanced.Graphics.AdvancedGraphics.saturationMultiplier.get().floatValue();
				float brightnessMultiplier = Config.Client.Advanced.Graphics.AdvancedGraphics.brightnessMultiplier.get().floatValue();
				if (saturationMultiplier == 1.0 && brightnessMultiplier == 1.0)
				{
					color = RenderDataPointUtil.getColor(data);
				}
				else
				{
					float[] ahsv = ColorUtil.argbToAhsv(RenderDataPointUtil.getColor(data));
					color = ColorUtil.ahsvToArgb(ahsv[0], ahsv[1], ahsv[2] * saturationMultiplier, ahsv[3] * brightnessMultiplier);
				}
				break;
			}
			case SHOW_DETAIL:
			{
				color = LodUtil.DEBUG_DETAIL_LEVEL_COLORS[detailLevel];
				fullBright = true;
				break;
			}
			case SHOW_BLOCK_MATERIAL:
			{
				
				switch (EDhApiBlockMaterial.getFromIndex(blockMaterialId))
				{
					case UNKNOWN:
					case AIR: // shouldn't normally be rendered, but just in case
						color = ColorUtil.HOT_PINK;
						break;
					
					case LEAVES:
						color = ColorUtil.DARK_GREEN;
						break;
					case STONE:
						color = ColorUtil.GRAY;
						break;
					case WOOD:
						color = ColorUtil.BROWN;
						break;
					case METAL:
						color = ColorUtil.DARK_GRAY;
						break;
					case DIRT:
						color = ColorUtil.LIGHT_BROWN;
						break;
					case LAVA:
						color = ColorUtil.ORANGE;
						break;
					case DEEPSLATE:
						color = ColorUtil.BLACK;
						break;
					case SNOW:
						color = ColorUtil.WHITE;
						break;
					case SAND:
						color = ColorUtil.TAN;
						break;
					case TERRACOTTA:
						color = ColorUtil.DARK_ORANGE;
						break;
					case NETHER_STONE:
						color = ColorUtil.DARK_RED;
						break;
					case WATER:
						color = ColorUtil.BLUE;
						break;
					case GRASS:
						color = ColorUtil.GREEN;
						break;
					case ILLUMINATED:
						color = ColorUtil.YELLOW;
						break;
					
					default:
						// undefined color
						color = ColorUtil.CYAN;
						break;
				}
				
				fullBright = true;
				break;
			}
			case SHOW_OVERLAPPING_QUADS:
			{
				color = ColorUtil.WHITE;
				fullBright = true;
				break;
			}
			case SHOW_RENDER_SOURCE_FLAG:
			{
				color = debugSource == null ? ColorUtil.RED : debugSource.color;
				fullBright = true;
				break;
			}
			default:
				throw new IllegalArgumentException("Unknown debug mode: " + debugging);
		}
		
		ColumnBox.addBoxQuadsToBuilder(
				quadBuilder, // buffer
				width, ySize, width, // setWidth
				x, yMin, z, // setOffset
				color, // setColor
				blockMaterialId, // irisBlockMaterialId
				RenderDataPointUtil.getLightSky(data), // setSkyLights
				fullBright ? 15 : RenderDataPointUtil.getLightBlock(data), // setBlockLights
				topData, bottomData, adjColumnViews); // setAdjData
	}
	
	
	
	//=================//
	// vbo interaction //
	//=================//
	
	public static GLVertexBuffer[] resizeBuffer(GLVertexBuffer[] vbos, int newSize)
	{
		if (vbos.length == newSize)
		{
			return vbos;
		}
		
		GLVertexBuffer[] newVbos = new GLVertexBuffer[newSize];
		System.arraycopy(vbos, 0, newVbos, 0, Math.min(vbos.length, newSize));
		if (newSize < vbos.length)
		{
			for (int i = newSize; i < vbos.length; i++)
			{
				if (vbos[i] != null)
				{
					vbos[i].close();
				}
			}
		}
		return newVbos;
	}
	
}
