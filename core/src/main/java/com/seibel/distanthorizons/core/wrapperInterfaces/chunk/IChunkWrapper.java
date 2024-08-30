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

import com.seibel.distanthorizons.core.generation.AdjacentChunkHolder;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import org.jetbrains.annotations.Nullable;

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
	void clearDhSkyLighting();
	
	int getDhBlockLight(int relX, int relY, int relZ);
	void setDhBlockLight(int relX, int relY, int relZ, int lightValue);
	void clearDhBlockLighting();
	
	int getBlockLight(int relX, int relY, int relZ);
	int getSkyLight(int relX, int relY, int relZ);
	
	
	/** Note: don't modify this array, it will only be generated once and then shared between uses */
	ArrayList<DhBlockPos> getWorldBlockLightPosList();
	
	
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
	 * @return true if the chunk's lighting was successfully populated, false otherwise
	 */
	@Deprecated
	default boolean bakeDhLightingUsingMcLightingEngine(ILevelWrapper levelWrapper) throws IllegalStateException
	{
		if (!this.isLightCorrect())
		{
			return false;
		}
		
		//=======================//
		// get lighting for each //
		// relative block pos    //
		//=======================//
		
		boolean lightingFound = false;
		// if the level doesn't have sky lights, then this check can be ignored
		// since all sky light values will be 0 anyway
		boolean skyLightingFound = !levelWrapper.hasSkyLight();
		
		for (int relX = 0; relX < LodUtil.CHUNK_WIDTH; relX++)
		{
			for (int relZ = 0; relZ < LodUtil.CHUNK_WIDTH; relZ++)
			{
				for (int y = this.getMinBuildHeight(); y < this.getMaxBuildHeight(); y++)
				{
					int skyLight = this.getSkyLight(relX, y, relZ);
					this.setDhSkyLight(relX, y, relZ, skyLight);
					int blockLight = this.getBlockLight(relX, y, relZ);
					this.setDhBlockLight(relX, y, relZ, blockLight);
					
					// MC defaults to max sky light and no block light, including underground blocks.
					// If any position has something different then those default values, it's likely that the
					// lighting was properly populated for at least part of the chunk
					if (!lightingFound &&
						(skyLight != LodUtil.MAX_MC_LIGHT || blockLight != LodUtil.MIN_MC_LIGHT))
					{
						lightingFound = true;
					}
					
					if (!skyLightingFound
						&& skyLight != LodUtil.MIN_MC_LIGHT)
					{
						skyLightingFound = true;
					}
				}
			}
		}
		
		
		
		//=================//
		// validate result //
		//=================//
		
		// if no lighting was found or the sky is always black, the lighting is likely broken
		if (!lightingFound || !skyLightingFound
			// if lighting is no longer correct or doesn't match the saved values
			// its very likely it broke halfway through and will need regenerating
			|| !this.isLightCorrect()
			|| this.getSkyLight(0, 0, 0) != this.getDhSkyLight(0,0,0)
			|| this.getBlockLight(0, 0, 0) != this.getDhBlockLight(0,0,0))
		{
			return false;
		}
		
		
		// lighting is valid
		this.setIsDhLightCorrect(true);
		this.setUseDhLighting(true);
		return true;
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
		int primeHeightMultiplier = 137;
		
		int minBuildHeight = this.getMinNonEmptyHeight();
		int maxBuildHeight = this.getMaxNonEmptyHeight();
		
		
		// most blocks (only some blocks are sampled since checking every block is a very slow operation)
		for (int x = 0; x < LodUtil.CHUNK_WIDTH; x+=2)
		{
			for (int z = 0; z < LodUtil.CHUNK_WIDTH; z+=2)
			{
				for (int y = minBuildHeight; y < maxBuildHeight; y+=2)
				{
					hash = (hash * primeBlockMultiplier) + this.getBlockState(x, y, z).hashCode();
					hash = (hash * primeBiomeMultiplier) + this.getBiome(x, y, z).hashCode();
					hash = (hash * primeHeightMultiplier) + y;
				}
			}
		}
		
		// surface (this should cover most cases for when users modify chunks)
		for (int x = 0; x < LodUtil.CHUNK_WIDTH; x++)
		{
			for (int z = 0; z < LodUtil.CHUNK_WIDTH; z++)
			{
				int lightBlockingY = this.getLightBlockingHeightMapValue(x, z);
				hash = (hash * primeBlockMultiplier) + this.getBlockState(x, lightBlockingY, z).hashCode();
				hash = (hash * primeBiomeMultiplier) + this.getBiome(x, lightBlockingY, z).hashCode();
				hash = (hash * primeHeightMultiplier) + lightBlockingY;
				
				int solidY = this.getSolidHeightMapValue(x, z);
				if (solidY != lightBlockingY)
				{
					hash = (hash * primeBlockMultiplier) + this.getBlockState(x, solidY, z).hashCode();
					hash = (hash * primeBiomeMultiplier) + this.getBiome(x, solidY, z).hashCode();
					hash = (hash * primeHeightMultiplier) + solidY;
				}
			}
		}
		
		// light emitting blocks (if the light changes then the LOD definitely needs to be updated)
		final DhBlockPos relPos = new DhBlockPos(); 
		ArrayList<DhBlockPos> lightPosList = this.getWorldBlockLightPosList();
		for (int i = 0; i < lightPosList.size(); i++)
		{
			DhBlockPos pos = lightPosList.get(i);
			pos.mutateToChunkRelativePos(relPos);
			
			hash = (hash * primeBlockMultiplier) + this.getBlockState(relPos.x, relPos.y, relPos.z).hashCode();
			hash = (hash * primeHeightMultiplier) + relPos.y;
		}
		
		
		return hash;
	}
	
	default List<BeaconBeamDTO> getAllActiveBeacons(ArrayList<IChunkWrapper> neighbourChunkList)
	{
		ArrayList<BeaconBeamDTO> beaconBeamList = new ArrayList<>();
		
		AdjacentChunkHolder adjacentChunkHolder = new AdjacentChunkHolder(this, neighbourChunkList);
		
		// since beacons emit light we can check only the positions that are emitting light
		final DhBlockPos relPos = new DhBlockPos();
		ArrayList<DhBlockPos> blockPosList = this.getWorldBlockLightPosList();
		for (int i = 0; i < blockPosList.size(); i++)
		{
			DhBlockPos pos = blockPosList.get(i);
			pos.mutateToChunkRelativePos(relPos);
			
			
			IBlockStateWrapper block = this.getBlockState(relPos);
			if (block.isBeaconBlock())
			{
				Color beaconColor = getBeaconColor(pos, adjacentChunkHolder);
				if (beaconColor != null)
				{
					BeaconBeamDTO beam = new BeaconBeamDTO(blockPosList.get(i), beaconColor);
					beaconBeamList.add(beam);
				}
			}
		}
		
		return beaconBeamList;
	}
	/** @return Null if the position isn't valid for a beacon beam. */
	@Nullable
	static Color getBeaconColor(DhBlockPos beaconPos, AdjacentChunkHolder chunkHolder) 
	{
		DhBlockPos beaconRelPos = beaconPos.createChunkRelativePos();
		DhBlockPos baseRelPos = new DhBlockPos(0, beaconRelPos.y-1, 0);
		
		
		
		//===========================//
		// check for the base blocks //
		//===========================//
		
		for (int x = -1; x<= 1; x++) 
		{
			for (int z = -1; z <= 1; z++)
			{
				baseRelPos.x = beaconRelPos.x + x;
				baseRelPos.z = beaconRelPos.z + z;
				baseRelPos.mutateToChunkRelativePos(baseRelPos);
				
				IChunkWrapper chunk = chunkHolder.getByBlockPos(beaconPos.x + x, beaconPos.z + z);
				if (chunk != null)
				{
					IBlockStateWrapper block = chunk.getBlockState(baseRelPos.x, baseRelPos.y, baseRelPos.z);
					if (!block.isBeaconBaseBlock())
					{
						return null;
					}
				}
			}
		}
		
		
		
		//=========================//
		// get the beacon color    //
		// and check for occlusion //
		//=========================//
		
		int red = 0;
		int green = 0;
		int blue = 0;
		boolean glassBlockFound = false;
		
		IChunkWrapper centerChunk = chunkHolder.getByBlockPos(beaconPos.x, beaconPos.z);
		int maxY = centerChunk.getMaxNonEmptyHeight();
		for (int y = beaconRelPos.y+1; y <= maxY; y++)
		{
			IBlockStateWrapper block = centerChunk.getBlockState(beaconRelPos.x, y, beaconRelPos.z);
			if (!block.isAir() && block.getOpacity() == LodUtil.BLOCK_FULLY_OPAQUE)
			{
				return null;
			}
			
			if (block.isGlassBlock() 
				// ignore invisible blocks (which have pure black as their map color, luckily black stained-glass is actually extremely dark gray)
				&& !block.getMapColor().equals(Color.BLACK))
			{
 				red += block.getMapColor().getRed();
				green += block.getMapColor().getGreen();
				blue += block.getMapColor().getBlue();
				
				if (glassBlockFound)
				{
					red /= 2;
					green /= 2;
					blue /= 2;
				}
				glassBlockFound = true;
			}
		}
		
		return glassBlockFound ? new Color(red, green, blue) : Color.WHITE;
	}
	
	
}
