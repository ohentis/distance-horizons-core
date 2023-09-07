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

package com.seibel.distanthorizons.core.generation;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This logic was roughly based on
 * <a href="https://github.com/PaperMC/Starlight/blob/acc8ed9634bbe27ec68e8842e420948bfa9707e7/TECHNICAL_DETAILS.md">Starlight's technical documentation</a>
 * although there were some changes due to how our lighting engine works in comparison to Minecraft's.
 */
public class DhLightingEngine
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	public static final DhLightingEngine INSTANCE = new DhLightingEngine();
	
	
	private DhLightingEngine() { }
	
	
	
	/**
	 * Note: depending on the implementation of {@link IChunkWrapper#setDhBlockLight(int, int, int, int)} and {@link IChunkWrapper#setDhSkyLight(int, int, int, int)}
	 * the light values may be stored in the wrapper itself instead of the wrapped chunk object.
	 * If that is the case unwrapping the chunk will undo any work this method did.
	 *
	 * @param centerChunk the chunk we want to apply lighting to
	 * @param nearbyChunkList should also contain centerChunk
	 * @param maxSkyLight should be a value between 0 and 15
	 */
	public void lightChunk(IChunkWrapper centerChunk, List<IChunkWrapper> nearbyChunkList, int maxSkyLight)
	{
		DhChunkPos centerChunkPos = centerChunk.getChunkPos();
		AdjacentChunkHolder adjacentChunkHolder = new AdjacentChunkHolder(centerChunk);
		
		
		// try-finally to handle the stableArray resources
		StableLightPosStack blockLightPosQueue = null;
		StableLightPosStack skyLightPosQueue = null;
		try
		{
			blockLightPosQueue = StableLightPosStack.borrowStableLightPosArray();
			skyLightPosQueue = StableLightPosStack.borrowStableLightPosArray();
			
			
			
			// generate the list of chunk pos we need,
			// currently a 3x3 grid
			HashSet<DhChunkPos> requestedAdjacentPositions = new HashSet<>(9);
			for (int xOffset = -1; xOffset <= 1; xOffset++)
			{
				for (int zOffset = -1; zOffset <= 1; zOffset++)
				{
					DhChunkPos adjacentPos = new DhChunkPos(centerChunkPos.x + xOffset, centerChunkPos.z + zOffset);
					requestedAdjacentPositions.add(adjacentPos);
				}
			}
			
			
			// find all adjacent chunks
			// and get any necessary info from them
			boolean warningLogged = false;
			for (IChunkWrapper chunk : nearbyChunkList)
			{
				if (chunk != null && requestedAdjacentPositions.contains(chunk.getChunkPos()))
				{
					// remove the newly found position
					requestedAdjacentPositions.remove(chunk.getChunkPos());
					
					// add the adjacent chunk
					adjacentChunkHolder.add(chunk);
					
					
					
					// get and set the adjacent chunk's initial block lights
					List<DhBlockPos> blockLightPosList = chunk.getBlockLightPosList();
					for (DhBlockPos blockLightPos : blockLightPosList)
					{
						// get the light
						DhBlockPos relLightBlockPos = blockLightPos.convertToChunkRelativePos();
						IBlockStateWrapper blockState = chunk.getBlockState(relLightBlockPos);
						int lightValue = blockState.getLightEmission();
						blockLightPosQueue.push(blockLightPos.x, blockLightPos.y, blockLightPos.z, lightValue);
						
						// set the light
						DhBlockPos relBlockPos = blockLightPos.convertToChunkRelativePos();
						chunk.setDhBlockLight(relBlockPos.x, relBlockPos.y, relBlockPos.z, lightValue);
					}
					
					
					// get and set the adjacent chunk's initial skylights,
					// if the dimension has skylights
					if (maxSkyLight > 0)
					{
						// get the adjacent chunk's sky lights
						for (int relX = 0; relX < LodUtil.CHUNK_WIDTH; relX++) // relative block pos
						{
							for (int relZ = 0; relZ < LodUtil.CHUNK_WIDTH; relZ++)
							{
								// get the light
								int maxY = chunk.getLightBlockingHeightMapValue(relX, relZ);
								DhBlockPos skyLightPos = new DhBlockPos(chunk.getMinBlockX() + relX, maxY, chunk.getMinBlockZ() + relZ);
								if (skyLightPos.y < chunk.getMinBuildHeight() || skyLightPos.y > chunk.getMaxBuildHeight())
								{
									// this shouldn't normally happen
									if (!warningLogged)
									{
										warningLogged = true;
										LOGGER.debug("Lighting chunk at pos " + chunk.getChunkPos() + " may have a missing or incomplete heightmap. Chunk min/max [" + chunk.getMinBuildHeight() + "/" + chunk.getMaxBuildHeight() + "], skylight pos: " + skyLightPos);
									}
									continue;
								}
								skyLightPosQueue.push(skyLightPos.x, skyLightPos.y, skyLightPos.z, maxSkyLight);
								
								
								// set the light
								DhBlockPos relBlockPos = skyLightPos.convertToChunkRelativePos();
								chunk.setDhSkyLight(relBlockPos.x, relBlockPos.y, relBlockPos.z, maxSkyLight);
							}
						}
					}
				}
				
				
				if (requestedAdjacentPositions.isEmpty())
				{
					// we found every chunk we needed, we don't need to keep iterating
					break;
				}
			}
			
			// validate that at least 1 chunk was found
			if (adjacentChunkHolder.size() == 0)
			{
				LOGGER.warn("Attempted to generate lighting for position [" + centerChunkPos + "], but neither that chunk nor any adjacent chunks were found. No chunk lighting was performed.");
				return;
			}
			
			
			
			// block light
			this.propagateLightPosList(blockLightPosQueue, adjacentChunkHolder,
					(neighbourChunk, relBlockPos) -> neighbourChunk.getDhBlockLight(relBlockPos.x, relBlockPos.y, relBlockPos.z),
					(neighbourChunk, relBlockPos, newLightValue) -> neighbourChunk.setDhBlockLight(relBlockPos.x, relBlockPos.y, relBlockPos.z, newLightValue));
			
			// sky light
			this.propagateLightPosList(skyLightPosQueue, adjacentChunkHolder,
					(neighbourChunk, relBlockPos) -> neighbourChunk.getDhSkyLight(relBlockPos.x, relBlockPos.y, relBlockPos.z),
					(neighbourChunk, relBlockPos, newLightValue) -> neighbourChunk.setDhSkyLight(relBlockPos.x, relBlockPos.y, relBlockPos.z, newLightValue));
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected lighting issue for center chunk: "+centerChunkPos, e);
		}
		finally
		{
			StableLightPosStack.returnStableLightPosArray(blockLightPosQueue);
			StableLightPosStack.returnStableLightPosArray(skyLightPosQueue);
		}
		
		
		
		centerChunk.setIsDhLightCorrect(true);
		LOGGER.trace("Finished generating lighting for chunk: [" + centerChunkPos + "]");
	}
	
	/** Applies each {@link LightPos} from the queue to the given set of {@link IChunkWrapper}'s. */
	private void propagateLightPosList(
			StableLightPosStack lightPosQueue, AdjacentChunkHolder adjacentChunkHolder,
			IGetLightFunc getLightFunc, ISetLightFunc setLightFunc)
	{
		// these objects are saved so they can be mutated throughout the method,
		// this reduces the number of allocations necessary, reducing GC pressure
		final DhBlockPos neighbourBlockPos = new DhBlockPos();
		final DhBlockPos relNeighbourBlockPos = new DhBlockPos();
		
		
		// update each light position
		while (!lightPosQueue.isEmpty())
		{
			// since we don't care about the order the positions are processed,
			// we can grab the last position instead of the first for a slight performance increase (this way the array doesn't need to be shifted over every loop)
			LightPos lightPos = lightPosQueue.pop();
			
			DhBlockPos pos = lightPos.pos;
			int lightValue = lightPos.lightValue;
			
			
			// propagate the lighting in each cardinal direction, IE: -x, +x, -y, +y, -z, +z
			for (EDhDirection direction : EDhDirection.CARDINAL_DIRECTIONS)
			{
				pos.offset(direction, neighbourBlockPos); // mutates neighbourBlockPos
				// converting the block pos into a relative position is necessary for accessing the light values in the chunk
				neighbourBlockPos.convertToChunkRelativePos(relNeighbourBlockPos); // mutates relNeighbourBlockPos
				
				
				// only continue if the light position is inside one of our chunks
				IChunkWrapper neighbourChunk = adjacentChunkHolder.getByBlockPos(neighbourBlockPos.x, neighbourBlockPos.z);
				if (neighbourChunk == null)
				{
					// the light pos is outside our generator's range, ignore it
					continue;
				}
				
				if (relNeighbourBlockPos.y < neighbourChunk.getMinBuildHeight() || relNeighbourBlockPos.y > neighbourChunk.getMaxBuildHeight())
				{
					// the light pos is outside the chunk's min/max height,
					// this can happen if given a chunk that hasn't finished generating
					continue;
				}
				
				
				int currentBlockLight = getLightFunc.getLight(neighbourChunk, relNeighbourBlockPos);
				if (currentBlockLight >= (lightValue - 1))
				{
					// short circuit for when the light value at this position
					// is already greater-than what we could set it
					continue;
				}
				
				
				IBlockStateWrapper neighbourBlockState = neighbourChunk.getBlockState(relNeighbourBlockPos);
				// Math.max(1, ...) is used so that the propagated light level always drops by at least 1, preventing infinite cycles.
				int targetLevel = lightValue - Math.max(1, neighbourBlockState.getOpacity());
				if (targetLevel > currentBlockLight)
				{
					// this position is darker than the new light value, update/set it
					setLightFunc.setLight(neighbourChunk, relNeighbourBlockPos, targetLevel);
					
					// now that light has been propagated to this blockPos
					// we need to queue it up so its neighbours can be propagated as well
					lightPosQueue.push(neighbourBlockPos.x, neighbourBlockPos.y, neighbourBlockPos.z, targetLevel);
				}
			}
		}
		
		// propagation complete
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	@FunctionalInterface
	interface IGetLightFunc { int getLight(IChunkWrapper chunk, DhBlockPos pos); }
	
	@FunctionalInterface
	interface ISetLightFunc { void setLight(IChunkWrapper chunk, DhBlockPos pos, int lightValue); }
	
	private static class LightPos
	{
		public final DhBlockPos pos;
		public int lightValue;
		
		public LightPos(DhBlockPos pos, int lightValue)
		{
			this.pos = pos;
			this.lightValue = lightValue;
		}
		
	}
	
	/** holds the adjacent chunks without having to create new Pos objects */
	private static class AdjacentChunkHolder
	{
		ArrayList<IChunkWrapper> chunkArray = new ArrayList<>(9);
		
		
		public AdjacentChunkHolder(IChunkWrapper centerWrapper)
		{
			this.chunkArray.add(centerWrapper);
		}
		
		
		public int size() { return this.chunkArray.size(); }
		
		public void add(IChunkWrapper centerWrapper) { this.chunkArray.add(centerWrapper); }
		
		public IChunkWrapper getByBlockPos(int blockX, int blockZ)
		{
			// >> 4 is equivalent to dividing by 16
			int chunkX = blockX >> 4;
			int chunkZ = blockZ >> 4;
			
			// since there will only ever be 9 items in the array, this sequential search should be fast enough
			for (IChunkWrapper chunk : this.chunkArray)
			{
				if (chunk != null 
					&& chunk.getChunkPos().x == chunkX && chunk.getChunkPos().z == chunkZ)
				{
					return chunk;
				}
			}
			return null;
		}
	}
	
	/** 
	 * Holds all potential {@link LightPos} objects a lighting task may need.
	 * This is done so existing {@link LightPos} objects can be repurposed instead of destroyed,
	 * reducing garbage collector load.
	 */
	private static class StableLightPosStack
	{
		/** necessary to prevent multiple threads from modifying the cache at once */
		private static final ReentrantLock cacheLock = new ReentrantLock();
		private static final Queue<StableLightPosStack> lightArrayCache = new ArrayDeque<>();
		
		/** the index of the last item in the array, -1 if empty */
		private int index = -1;
		
		// when tested with a normal 1.20 world James saw a maximum of 36,709 block and 2,355 sky lights,
		// so this should give us a good base that should be able to contain most lighting tasks
		private final ArrayList<LightPos> arrayList = new ArrayList<>(40_000);
		
		
		
		//================//
		// cache handling //
		//================//
		
		private static StableLightPosStack borrowStableLightPosArray()
		{
			try
			{
				// prevent multiple threads modifying the cache at once
				cacheLock.lock();
				
				return lightArrayCache.isEmpty() ? new StableLightPosStack() : lightArrayCache.remove();
			}
			finally
			{
				cacheLock.unlock();
			}
		}
		
		private static void returnStableLightPosArray(StableLightPosStack stableArray)
		{
			try
			{
				// prevent multiple threads modifying the cache at once
				cacheLock.lock();
				
				if (stableArray != null)
				{
					lightArrayCache.add(stableArray);
				}
			}
			finally
			{
				cacheLock.unlock();
			}
		}
		
		
		
		//===============//
		// stack methods //
		//===============//
		
		public boolean isEmpty() { return this.index == -1; }
		public int size() { return this.index+1; }
		
		public void push(int blockX, int blockY, int blockZ, int lightValue)
		{
			this.index++;
			if (this.index < this.arrayList.size())
			{
				// modify the existing pos in the array
				LightPos lightPos = this.arrayList.get(this.index);
				lightPos.pos.x = blockX;
				lightPos.pos.y = blockY;
				lightPos.pos.z = blockZ;
				lightPos.lightValue = lightValue;
			}
			else
			{
				// add a new pos
				this.arrayList.add(new LightPos(new DhBlockPos(blockX, blockY, blockZ), lightValue));
			}
		}
		
		public LightPos pop()
		{
			LightPos pos = this.arrayList.get(this.index);
			this.index--;
			return pos;
		}
		
		@Override
		public String toString() { return this.index + "/" + this.arrayList.size(); }
		
	}
	
}
