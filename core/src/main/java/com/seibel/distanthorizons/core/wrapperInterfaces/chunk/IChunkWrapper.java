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

package com.seibel.distanthorizons.core.wrapperInterfaces.chunk;

import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public interface IChunkWrapper extends IBindable
{
	/** useful for debugging, but can slow down chunk operations quite a bit due to being called every time. */
	boolean RUN_RELATIVE_POS_INDEX_VALIDATION = ModInfo.IS_DEV_BUILD;
	
	
	
	DhChunkPos getChunkPos();
	
	default int getHeight() { return this.getMaxBuildHeight() - this.getMinBuildHeight(); }
	int getMinBuildHeight();
	int getMaxBuildHeight();
	
	/**
	 * returns the Y level for the last non-empty section in this chunk,
	 * or {@link IChunkWrapper#getMinBuildHeight()} if this chunk is completely empty.
	 */
	int getMinNonEmptyHeight();
	/**
	 * returns the Y level for the first non-empty section in this chunk,
	 * or {@link IChunkWrapper#getMaxBuildHeight()} if this chunk is completely empty.
	 */
	int getMaxNonEmptyHeight();
	
	/** @return The highest y position of a solid block at the given relative chunk position. */
	int getSolidHeightMapValue(int xRel, int zRel);
	/**
	 * @return The highest y position of a light-blocking or translucent block at the given relative chunk position. <br>
	 * Note: this includes water.
	 */
	int getLightBlockingHeightMapValue(int xRel, int zRel);
	
	int getMaxBlockX();
	int getMaxBlockZ();
	int getMinBlockX();
	int getMinBlockZ();
	
	void setIsDhLightCorrect(boolean isDhLightCorrect);
	void setUseDhLighting(boolean useDhLighting);
	boolean isLightCorrect();
	
	
	int getDhSkyLight(int relX, int relY, int relZ);
	void setDhSkyLight(int relX, int relY, int relZ, int lightValue);
	
	int getDhBlockLight(int relX, int relY, int relZ);
	void setDhBlockLight(int relX, int relY, int relZ, int lightValue);
	
	int getBlockLight(int relX, int relY, int relZ);
	int getSkyLight(int relX, int relY, int relZ);
	
	
	ArrayList<DhBlockPos> getBlockLightPosList();
	
	
	default boolean blockPosInsideChunk(DhBlockPos blockPos) { return this.blockPosInsideChunk(blockPos.x, blockPos.y, blockPos.z); }
	default boolean blockPosInsideChunk(int x, int y, int z)
	{
		return (x >= this.getMinBlockX() && x <= this.getMaxBlockX()
				&& y >= this.getMinBuildHeight() && y < this.getMaxBuildHeight()
				&& z >= this.getMinBlockZ() && z <= this.getMaxBlockZ());
	}
	default boolean blockPosInsideChunk(DhBlockPos2D blockPos)
	{
		return (blockPos.x >= this.getMinBlockX() && blockPos.x <= this.getMaxBlockX()
				&& blockPos.z >= this.getMinBlockZ() && blockPos.z <= this.getMaxBlockZ());
	}
	
	boolean doNearbyChunksExist();
	String toString();
	
	
	default IBlockStateWrapper getBlockState(DhBlockPos pos) { return this.getBlockState(pos.x, pos.y, pos.z); }
	IBlockStateWrapper getBlockState(int relX, int relY, int relZ);
	
	IBiomeWrapper getBiome(int relX, int relY, int relZ);
	
	boolean isStillValid();
	
	
	
	//========================//
	// default helper methods //
	//========================//
	
	/** used to prevent accidentally attempting to get/set values outside this chunk's boundaries */
	default void throwIndexOutOfBoundsIfRelativePosOutsideChunkBounds(int x, int y, int z) throws IndexOutOfBoundsException
	{
		if (!RUN_RELATIVE_POS_INDEX_VALIDATION)
		{
			return;
		}
		
		
		// FIXME +1 is to handle the fact that LodDataBuilder adds +1 to all block lighting calculations, also done in the constructor
		int minHeight = this.getMinBuildHeight();
		int maxHeight = this.getMaxBuildHeight() + 1;
		
		if (x < 0 || x >= LodUtil.CHUNK_WIDTH
				|| z < 0 || z >= LodUtil.CHUNK_WIDTH
				|| y < minHeight || y > maxHeight)
		{
			String errorMessage = "Relative position [" + x + "," + y + "," + z + "] out of bounds. \n" +
					"X/Z must be between 0 and 15 (inclusive) \n" +
					"Y must be between [" + minHeight + "] and [" + maxHeight + "] (inclusive).";
			throw new IndexOutOfBoundsException(errorMessage);
		}
	}
	
	
	/**
	 * Populates DH's saved lighting using MC's lighting engine.
	 * This is generally done in cases where MC's lighting is correct now, but may not be later (like when a chunk is unloading).
	 *
	 * @throws IllegalStateException if the chunk's lighting isn't valid. This is done to prevent accidentally baking broken lighting.
	 */
	default void bakeDhLightingUsingMcLightingEngine() throws IllegalStateException
	{
		if (!this.isLightCorrect())
		{
			throw new IllegalStateException("Unable to bake lighting for for chunk [" + this.getChunkPos() + "], Minecraft lighting not valid.");
		}
		
		// get the lighting for every relative block pos
		for (int relX = 0; relX < LodUtil.CHUNK_WIDTH; relX++)
		{
			for (int relZ = 0; relZ < LodUtil.CHUNK_WIDTH; relZ++)
			{
				for (int y = this.getMinBuildHeight(); y < this.getMaxBuildHeight(); y++)
				{
					this.setDhSkyLight(relX, y, relZ, this.getSkyLight(relX, y, relZ));
					this.setDhBlockLight(relX, y, relZ, this.getBlockLight(relX, y, relZ));
				}
			}
		}
		
		this.setIsDhLightCorrect(true);
		this.setUseDhLighting(true);
	}
	
	
	/**
	 * Converts a 3D position into a 1D array index. <br><br>
	 *
	 * Source: <br>
	 * <a href="https://stackoverflow.com/questions/7367770/how-to-flatten-or-index-3d-array-in-1d-array">stackoverflow</a>
	 */
	default int relativeBlockPosToIndex(int xRel, int y, int zRel)
	{
		int yRel = y - this.getMinBuildHeight();
		return (zRel * LodUtil.CHUNK_WIDTH * this.getHeight()) + (yRel * LodUtil.CHUNK_WIDTH) + xRel;
	}
	
	/**
	 * Converts a 3D position into a 1D array index. <br><br>
	 *
	 * Source: <br>
	 * <a href="https://stackoverflow.com/questions/7367770/how-to-flatten-or-index-3d-array-in-1d-array">stackoverflow</a>
	 */
	default DhBlockPos indexToRelativeBlockPos(int index)
	{
		final int zRel = index / (LodUtil.CHUNK_WIDTH * this.getHeight());
		index -= (zRel * LodUtil.CHUNK_WIDTH * this.getHeight());
		
		final int y = index / LodUtil.CHUNK_WIDTH;
		final int yRel = y + this.getMinBuildHeight();
		
		final int xRel = index % LodUtil.CHUNK_WIDTH;
		return new DhBlockPos(xRel, yRel, zRel);
	}
	
	
	/** This is a bad hash algorithm since it only uses the heightmap, but can be used for rough debugging. */
	default int roughHashCode()
	{
		int hash = 31;
		int primeMultiplier = 227;
		
		for (int x = 0; x < LodUtil.CHUNK_WIDTH; x++)
		{
			for (int z = 0; z < LodUtil.CHUNK_WIDTH; z++)
			{
				hash = (hash * primeMultiplier) + Integer.hashCode(this.getLightBlockingHeightMapValue(x, z));
			}
		}
		
		return hash;
	}
	
	default int getBlockBiomeHashCode()
	{
		int hash = 31;
		int primeBlockMultiplier = 227;
		int primeBiomeMultiplier = 701;
		
		int minBuildHeight = this.getMinBuildHeight();
		int maxBuildHeight = this.getMaxBuildHeight();
		
		for (int x = 0; x < LodUtil.CHUNK_WIDTH; x++)
		{
			for (int z = 0; z < LodUtil.CHUNK_WIDTH; z++)
			{
				for (int y = minBuildHeight; y < maxBuildHeight; y++)
				{
					hash = (hash * primeBlockMultiplier) + this.getBlockState(x, y, z).hashCode();
					hash = (hash * primeBiomeMultiplier) + this.getBiome(x, y, z).hashCode();
				}
			}
		}
		
		return hash;
	}
	
	default List<BeaconBeamDTO> getAllActiveBeacons()
	{
		ArrayList<BeaconBeamDTO> beaconPosList = new ArrayList<>();
		
		// since beacons emit light we can check only the positions that are emitting light
		ArrayList<DhBlockPos> blockPosList = this.getBlockLightPosList();
		for (int i = 0; i < blockPosList.size(); i++)
		{
			DhBlockPos pos = blockPosList.get(i);
			DhBlockPos relPos = pos.convertToChunkRelativePos();
			
			IBlockStateWrapper block = this.getBlockState(relPos);
			if (block.getSerialString().toLowerCase().contains("minecraft:beacon"))
			{
				if (isBeaconActive(relPos.x, relPos.y, relPos.z, this))
				{
					BeaconBeamDTO beam = new BeaconBeamDTO(blockPosList.get(i), Color.WHITE);
					beaconPosList.add(beam);
				}
			}
		}
		
		return beaconPosList;
	}
	static boolean isBeaconActive(int relBlockX, int y, int relBlockZ, IChunkWrapper chunkWrapper) 
	{
		for (int x = -1; x<= 1; x++) 
		{
			for (int z = -1; z <= 1; z++)
			{
				if ((relBlockX + x < 0 || relBlockX + x >= LodUtil.CHUNK_WIDTH)
					|| (relBlockZ + z < 0 || relBlockZ + z >= LodUtil.CHUNK_WIDTH)) 
				{
					// if the beacon is on the border of a chunk and all other blocks are there, assume it's complete
					//TODO! Check adjacent chunk, if possible
					continue;
				}
				String blockId = chunkWrapper.getBlockState(relBlockX + x, y -1, relBlockZ + z).getSerialString();
				
				if (!(blockId.contains("diamond_block")
						|| blockId.contains("iron_block")
						|| blockId.contains("emerald_block")
						|| blockId.contains("netherite_block")
						|| blockId.contains("gold_block"))) 
				{
					return false;
				}
			}
		}
		return true;
	}
	
	
}
