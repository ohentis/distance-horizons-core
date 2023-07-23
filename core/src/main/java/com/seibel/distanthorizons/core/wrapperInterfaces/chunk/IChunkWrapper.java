/*
 *    This file is part of the Distant Horizons mod (formerly the LOD Mod),
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2022  James Seibel
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
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;

import java.util.List;

public interface IChunkWrapper extends IBindable
{
	DhChunkPos getChunkPos();
	
	default int getHeight() { return this.getMaxBuildHeight() - this.getMinBuildHeight(); }
	int getMinBuildHeight();
	int getMaxBuildHeight();
	
	/** @return The highest y position of a solid block at the given relative chunk position. */
	int getSolidHeightMapValue(int xRel, int zRel);
	/** 
	 * @return The highest y position of a light-blocking or translucent block at the given relative chunk position. <br> 
	 * 			Note: this includes water.
	 */
	int getLightBlockingHeightMapValue(int xRel, int zRel);
	
	int getMaxBlockX();
	int getMaxBlockZ();
	int getMinBlockX();
	int getMinBlockZ();

	long getLongChunkPos();
	
	void setIsDhLightCorrect(boolean isDhLightCorrect);
	boolean isLightCorrect();
	
	
	int getDhSkyLight(int relX, int relY, int relZ);
	void setDhSkyLight(int relX, int relY, int relZ, int lightValue);
	
	int getDhBlockLight(int relX, int relY, int relZ);
	void setDhBlockLight(int relX, int relY, int relZ, int lightValue);
	
	int getBlockLight(int relX, int relY, int relZ);
	int getSkyLight(int relX, int relY, int relZ);
	
	
	List<DhBlockPos> getBlockLightPosList();
	
	
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
	
	/** This is a bad hash algorithm, but can be used for rough debugging. */
	default int roughHashCode()
	{
		int hash = 31;
		int primeMultiplier = 227;
		
		for(int x = 0; x < LodUtil.CHUNK_WIDTH; x++)
		{
			for(int z = 0; z < LodUtil.CHUNK_WIDTH; z++)
			{
				hash = hash * primeMultiplier + Integer.hashCode(this.getLightBlockingHeightMapValue(x, z));
			}
		}
		
		return hash;
	}
	
	
	default IBlockStateWrapper getBlockState(DhBlockPos pos) { return this.getBlockState(pos.x, pos.y, pos.z); }
	IBlockStateWrapper getBlockState(int relX, int relY, int relZ);
	
	IBiomeWrapper getBiome(int relX, int relY, int relZ);

    boolean isStillValid();
}
