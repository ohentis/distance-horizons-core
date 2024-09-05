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

package com.seibel.distanthorizons.core.dataObjects.transformers;

import java.util.Collections;
import java.util.List;

import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPosMutable;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IMutableBlockPosWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.apache.logging.log4j.Logger;

public class LodDataBuilder
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	private static final IBlockStateWrapper AIR = SingletonInjector.INSTANCE.get(IWrapperFactory.class).getAirBlockStateWrapper();
	
	private static boolean getTopErrorLogged = false;
	
	
	
	//============//
	// converters //
	//============//
	
	public static FullDataSourceV2 createGeneratedDataSource(IChunkWrapper chunkWrapper)
	{
		if (!canGenerateLodFromChunk(chunkWrapper))
		{
			return null;
		}
		
		
		
		int sectionPosX = getXOrZSectionPosFromChunkPos(chunkWrapper.getChunkPos().getX());
		int sectionPosZ = getXOrZSectionPosFromChunkPos(chunkWrapper.getChunkPos().getZ());
		long pos = DhSectionPos.encode(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, sectionPosX, sectionPosZ);
		
		FullDataSourceV2 dataSource = FullDataSourceV2.createEmpty(pos);
		dataSource.isEmpty = false;
		
		
		
		// compute the chunk dataSource offset
		// this offset is used to determine where in the dataSource this chunk's data should go
		int chunkOffsetX = chunkWrapper.getChunkPos().getX();
		if (chunkWrapper.getChunkPos().getX() < 0)
		{
			// expected offset positions:
			// chunkPos -> offset
			//  5 -> 1
			//  4 -> 0 ---
			//  3 -> 3
			//  2 -> 2
			//  1 -> 1
			//  0 -> 0 ===
			// -1 -> 3
			// -2 -> 2
			// -3 -> 1
			// -4 -> 0 ---
			// -5 -> 3
			chunkOffsetX = ((chunkOffsetX) % FullDataSourceV2.NUMB_OF_CHUNKS_WIDE);
			if (chunkOffsetX != 0)
			{
				chunkOffsetX += FullDataSourceV2.NUMB_OF_CHUNKS_WIDE;
			}
		}
		else
		{
			chunkOffsetX %= FullDataSourceV2.NUMB_OF_CHUNKS_WIDE;
		}
		chunkOffsetX *= LodUtil.CHUNK_WIDTH;
		
		int chunkOffsetZ = chunkWrapper.getChunkPos().getZ();
		if (chunkWrapper.getChunkPos().getZ() < 0)
		{
			chunkOffsetZ = ((chunkOffsetZ) % FullDataSourceV2.NUMB_OF_CHUNKS_WIDE);
			if (chunkOffsetZ != 0)
			{
				chunkOffsetZ += FullDataSourceV2.NUMB_OF_CHUNKS_WIDE;
			}
		}
		else
		{
			chunkOffsetZ %= FullDataSourceV2.NUMB_OF_CHUNKS_WIDE;
		}
		chunkOffsetZ *= LodUtil.CHUNK_WIDTH;
		
		
		
		//==========================//
		// populate the data source //
		//==========================//
		
		EDhApiWorldCompressionMode worldCompressionMode = Config.Client.Advanced.LodBuilding.worldCompression.get();
		boolean ignoreHiddenBlocks = (worldCompressionMode != EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS);
		
		try
		{
			IMutableBlockPosWrapper mcBlockPos = chunkWrapper.getMutableBlockPosWrapper();
			IBlockStateWrapper previousBlockState = null;
			
			int minBuildHeight = chunkWrapper.getMinNonEmptyHeight();
			for (int relBlockX = 0; relBlockX < LodUtil.CHUNK_WIDTH; relBlockX++)
			{
				for (int relBlockZ = 0; relBlockZ < LodUtil.CHUNK_WIDTH; relBlockZ++)
				{
					LongArrayList longs = new LongArrayList(chunkWrapper.getHeight() / 4);
					int lastY = chunkWrapper.getMaxBuildHeight();
					IBiomeWrapper biome = chunkWrapper.getBiome(relBlockX, lastY, relBlockZ);
					IBlockStateWrapper blockState = AIR;
					int mappedId = dataSource.mapping.addIfNotPresentAndGetId(biome, blockState);
					
					
					byte blockLight;
					byte skyLight;
					if (lastY < chunkWrapper.getMaxBuildHeight())
					{
						// FIXME: The lastY +1 offset is to reproduce the old behavior. Remove this when we get per-face lighting
						blockLight = (byte) chunkWrapper.getBlockLight(relBlockX, lastY + 1, relBlockZ);
						skyLight = (byte) chunkWrapper.getSkyLight(relBlockX, lastY + 1, relBlockZ);
					}
					else
					{
						//we are at the height limit. There are no torches here, and sky is not obscured.
						blockLight = LodUtil.MIN_MC_LIGHT;
						skyLight = LodUtil.MAX_MC_LIGHT;
					}
					
					
					// determine the starting Y Pos
					int y = chunkWrapper.getLightBlockingHeightMapValue(relBlockX, relBlockZ);
					// go up until we reach open air or the world limit
					IBlockStateWrapper topBlockState = previousBlockState = chunkWrapper.getBlockState(relBlockX, y, relBlockZ, mcBlockPos, previousBlockState);
					while (!topBlockState.isAir() && y < chunkWrapper.getMaxBuildHeight())
					{
						try
						{
							// This is necessary in some edge cases with snow layers and some other blocks that may not appear in the height map but do block light.
							// Interestingly this doesn't appear to be the case in the DhLightingEngine, if this same logic is added there the lighting breaks for the affected blocks.
							y++;
							topBlockState = previousBlockState = chunkWrapper.getBlockState(relBlockX, y, relBlockZ, mcBlockPos, previousBlockState);
						}
						catch (Exception e)
						{
							if (!getTopErrorLogged)
							{
								LOGGER.warn("Unexpected issue in LodDataBuilder, future errors won't be logged. Chunk [" + chunkWrapper.getChunkPos() + "] with max height: [" + chunkWrapper.getMaxBuildHeight() + "] had issue getting block at pos [" + relBlockX + "," + y + "," + relBlockZ + "] error: " + e.getMessage(), e);
								getTopErrorLogged = true;
							}
							
							y--;
							break;
						}
					}
					
					
					for (; y >= minBuildHeight; y--)
					{
						IBiomeWrapper newBiome = chunkWrapper.getBiome(relBlockX, y, relBlockZ);
						IBlockStateWrapper newBlockState = previousBlockState = chunkWrapper.getBlockState(relBlockX, y, relBlockZ, mcBlockPos, previousBlockState);
						byte newBlockLight = (byte) chunkWrapper.getBlockLight(relBlockX, y + 1, relBlockZ);
						byte newSkyLight = (byte) chunkWrapper.getSkyLight(relBlockX, y + 1, relBlockZ);
						
						// save the biome/block change
						if (!newBiome.equals(biome) || !newBlockState.equals(blockState))
						{
							// if we ignore hidden blocks, don't save this biome/block change
							// wait until the block is visible and then save the new datapoint
							if (!ignoreHiddenBlocks
									// if the last block is air, this block will always be visible
									|| blockState.isAir()
									// check if this block is visible from any direction 
									|| blockVisible(chunkWrapper, relBlockX, y, relBlockZ))
							{
								longs.add(FullDataPointUtil.encode(mappedId, lastY - y, y + 1 - chunkWrapper.getMinBuildHeight(), blockLight, skyLight));
								biome = newBiome;
								blockState = newBlockState;
								
								mappedId = dataSource.mapping.addIfNotPresentAndGetId(biome, blockState);
								blockLight = newBlockLight;
								skyLight = newSkyLight;
								lastY = y;
							}
						}
					}
					longs.add(FullDataPointUtil.encode(mappedId, lastY - y, y + 1 - chunkWrapper.getMinBuildHeight(), blockLight, skyLight));
					
					dataSource.setSingleColumn(longs,
							relBlockX + chunkOffsetX,
							relBlockZ + chunkOffsetZ,
							EDhApiWorldGenerationStep.LIGHT,
							worldCompressionMode);
				}
			}
		}
		catch (DataCorruptedException e)
		{
			LOGGER.error("Unable to convert chunk at pos ["+chunkWrapper.getChunkPos()+"] to an LOD. Error: "+e.getMessage(), e);
			return null;
		}
		
		LodUtil.assertTrue(!dataSource.isEmpty);
		return dataSource;
	}
	private static boolean blockVisible(IChunkWrapper chunkWrapper, int relBlockX, int blockY, int relBlockZ)
	{
		DhBlockPos originalBlockPos = new DhBlockPos(relBlockX,blockY,relBlockZ);
		final DhBlockPosMutable testBlockPos = new DhBlockPosMutable(relBlockX,blockY,relBlockZ);
		
		// up/down
		if (blockInDirectionVisible(chunkWrapper, EDhDirection.UP, originalBlockPos, testBlockPos))
		{
			return true;
		}
		if (blockInDirectionVisible(chunkWrapper, EDhDirection.DOWN, originalBlockPos, testBlockPos))
		{
			return true;
		}
		
		// north/south
		if (blockInDirectionVisible(chunkWrapper, EDhDirection.NORTH, originalBlockPos, testBlockPos))
		{
			return true;
		}
		if (blockInDirectionVisible(chunkWrapper, EDhDirection.SOUTH, originalBlockPos, testBlockPos))
		{
			return true;
		}
		
		// east/west
		if (blockInDirectionVisible(chunkWrapper, EDhDirection.EAST, originalBlockPos, testBlockPos))
		{
			return true;
		}
		if (blockInDirectionVisible(chunkWrapper, EDhDirection.WEST, originalBlockPos, testBlockPos))
		{
			return true;
		}
		
		
		return false;
	}
	private static boolean blockInDirectionVisible(IChunkWrapper chunkWrapper, EDhDirection direction, DhBlockPos originalBlockPos, DhBlockPosMutable testBlockPos)
	{
		originalBlockPos.mutateOffset(direction, testBlockPos);
		
		// if the block is next to the border of a chunk, assume it's visible
		if (testBlockPos.getX() < 0 || testBlockPos.getX() >= LodUtil.CHUNK_WIDTH)
		{
			return true;
		}
		if (testBlockPos.getZ() < 0 || testBlockPos.getZ() >= LodUtil.CHUNK_WIDTH)
		{
			return true;
		}
		if (testBlockPos.getY() < chunkWrapper.getMinBuildHeight() || testBlockPos.getY() > chunkWrapper.getMaxBuildHeight())
		{
			return true;
		}
		
		// this block isn't on a chunk boundary, check if it is next to a transparent/air block
		IBlockStateWrapper blockState = chunkWrapper.getBlockState(testBlockPos);
		return blockState.isAir() || blockState.getOpacity() != LodUtil.BLOCK_FULLY_OPAQUE;
	}
	
	
	/** @throws ClassCastException if an API user returns the wrong object type(s) */
	public static FullDataSourceV2 createFromApiChunkData(DhApiChunk apiChunk, boolean runAdditionalValidation) throws ClassCastException, DataCorruptedException, IllegalArgumentException
	{
		// get the section position
		int sectionPosX = getXOrZSectionPosFromChunkPos(apiChunk.chunkPosX);
		int sectionPosZ = getXOrZSectionPosFromChunkPos(apiChunk.chunkPosZ);
		long pos = DhSectionPos.encode(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, sectionPosX, sectionPosZ);
		
		// chunk relative block position in the data source
		int relSourceBlockX = Math.floorMod(apiChunk.chunkPosX, 4) * LodUtil.CHUNK_WIDTH;
		int relSourceBlockZ = Math.floorMod(apiChunk.chunkPosZ, 4) * LodUtil.CHUNK_WIDTH;
		
		FullDataSourceV2 dataSource = FullDataSourceV2.createEmpty(pos);
		for (int relBlockZ = 0; relBlockZ < LodUtil.CHUNK_WIDTH; relBlockZ++)
		{
			for (int relBlockX = 0; relBlockX < LodUtil.CHUNK_WIDTH; relBlockX++)
			{
				List<DhApiTerrainDataPoint> columnDataPoints = apiChunk.getDataPoints(relBlockX, relBlockZ);
				if (runAdditionalValidation)
				{
					validateOrThrowDataColumn(columnDataPoints);
				}
				
				
				// this null check does 2 nice things at the same time:
				// if columnDataPoints is null,
				// then packedDataPoints will be of length 0
				// AND the below loop won't run.
				int size = (columnDataPoints != null) ? columnDataPoints.size() : 0;
				
				// TODO make missing air LODs
				// TODO merge duplicate datapoints
				LongArrayList packedDataPoints = new LongArrayList(new long[size]);
				for (int index = 0; index < size; index++)
				{
					DhApiTerrainDataPoint dataPoint = columnDataPoints.get(index);
					
					int id = dataSource.mapping.addIfNotPresentAndGetId(
							(IBiomeWrapper) (dataPoint.biomeWrapper),
							(IBlockStateWrapper) (dataPoint.blockStateWrapper)
					);
					
					packedDataPoints.set(index, FullDataPointUtil.encode(
							id,
							dataPoint.topYBlockPos - dataPoint.bottomYBlockPos,
							dataPoint.bottomYBlockPos - apiChunk.bottomYBlockPos,
							(byte) (dataPoint.blockLightLevel),
							(byte) (dataPoint.skyLightLevel)
					));
				}
				
				// TODO add the ability for API users to define a different compression mode
				//  or add a "unkown" compression mode
				dataSource.setSingleColumn(
						packedDataPoints, 
						relBlockX + relSourceBlockX, relBlockZ + relSourceBlockZ, 
						EDhApiWorldGenerationStep.LIGHT, EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS);
				dataSource.isEmpty = false;
			}
		}
		return dataSource;
	}
	private static void validateOrThrowDataColumn(List<DhApiTerrainDataPoint> dataPoints) throws IllegalArgumentException
	{
		// order doesn't need to be checked if there is 0 or 1 items
		if (dataPoints.size() > 1)
		{
			// DH expects datapoints to be in a top-down order
			DhApiTerrainDataPoint first = dataPoints.get(0);
			DhApiTerrainDataPoint last = dataPoints.get(dataPoints.size() - 1);
			if (first.bottomYBlockPos < last.bottomYBlockPos)
			{
				// flip the array if it's in bottom-up order
				Collections.reverse(dataPoints);
			}
			
		}
		
		
		
		// check that each datapoint is valid
		int lastBottomYPos = Integer.MIN_VALUE;
		for (int i = 0; i < dataPoints.size(); i++) // standard for-loop used instead of an enhanced for-loop to slightly reduce GC overhead due to iterator allocation
		{
			DhApiTerrainDataPoint dataPoint = dataPoints.get(i);
			
			if (dataPoint == null)
			{
				throw new IllegalArgumentException("Datapoint: ["+i+"] is null DhApiTerrainDataPoints are not allowed. If you want to represent empty terrain, please use AIR.");
			}
			
			if (dataPoint.detailLevel != 0)
			{
				throw new IllegalArgumentException("Datapoint: ["+i+"] has the wrong detail level ["+dataPoint.detailLevel+"], all data points must be block sized; IE their detail level must be [0].");
			}
			
			
			
			int bottomYPos = dataPoint.bottomYBlockPos;
			int topYPos = dataPoint.topYBlockPos;
			int height = (dataPoint.topYBlockPos - dataPoint.bottomYBlockPos);
			
			// is the datapoint right side up?
			if (bottomYPos > topYPos)
			{
				throw new IllegalArgumentException("Datapoint: ["+i+"] is upside down. Top Pos: ["+topYPos+"], bottom pos: ["+bottomYPos+"].");
			}
			// valid height?
			if (height <= 0 || height >= RenderDataPointUtil.MAX_WORLD_Y_SIZE)
			{
				throw new IllegalArgumentException("Datapoint: ["+i+"] has invalid height. Height must be in the range [1 - "+RenderDataPointUtil.MAX_WORLD_Y_SIZE+"] (inclusive).");
			}
			
			// is this datapoint overlapping the last one?
			if (lastBottomYPos > topYPos)
			{
				throw new IllegalArgumentException("DhApiTerrainDataPoint ["+i+"] is overlapping with the last datapoint, this top Y: ["+topYPos+"], lastBottomYPos: ["+lastBottomYPos+"].");
			}
			// is there a gap between the last datapoint?
			if (topYPos != lastBottomYPos
				&& lastBottomYPos != Integer.MIN_VALUE)
			{
				throw new IllegalArgumentException("DhApiTerrainDataPoint ["+i+"] has a gap between it and index ["+(i-1)+"]. Empty spaces should be filled by air, otherwise DH's downsampling won't calculate lighting correctly.");
			}
			
			
			lastBottomYPos = bottomYPos; 
		}
		
	}
	
	
	
	
	//================//
	// helper methods //
	//================//
	
	public static boolean canGenerateLodFromChunk(IChunkWrapper chunk) { return chunk != null && chunk.isLightCorrect(); }
	
	public static int getXOrZSectionPosFromChunkPos(int chunkXOrZPos)
	{
		// get the section position
		int sectionPos = chunkXOrZPos;
		// negative positions start at -1 so the logic there is slightly different
		sectionPos = (sectionPos < 0) ? ((sectionPos + 1) / FullDataSourceV2.NUMB_OF_CHUNKS_WIDE) - 1 : (sectionPos / FullDataSourceV2.NUMB_OF_CHUNKS_WIDE);
		return sectionPos;
	}
	
}
