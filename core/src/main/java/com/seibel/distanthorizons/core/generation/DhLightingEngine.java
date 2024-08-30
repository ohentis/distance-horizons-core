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

import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhBlockPosMutable;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jetbrains.annotations.NotNull;

/**
 * This logic was roughly based on
 * <a href="https://github.com/PaperMC/Starlight/blob/acc8ed9634bbe27ec68e8842e420948bfa9707e7/TECHNICAL_DETAILS.md">Starlight's technical documentation</a>
 * although there were some changes due to how our lighting engine works in comparison to Minecraft's.
 */
public class DhLightingEngine
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	public static final DhLightingEngine INSTANCE = new DhLightingEngine();
	
	/** 
	 * Minor garbage collection optimization. <br>
	 * Since these objects are always mutated anyway, using a {@link ThreadLocal} will allow us to
	 * only create as many of these {@link DhBlockPos} as necessary.
	 */
	private static final ThreadLocal<DhBlockPosMutable> PRIMARY_BLOCK_POS_REF = ThreadLocal.withInitial(() -> new DhBlockPosMutable());
	private static final ThreadLocal<DhBlockPosMutable> SECONDARY_BLOCK_POS_REF = ThreadLocal.withInitial(() -> new DhBlockPosMutable());
	
	
	
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
	public void lightChunk(@NotNull IChunkWrapper centerChunk, @NotNull ArrayList<IChunkWrapper> nearbyChunkList, int maxSkyLight)
	{
		DhChunkPos centerChunkPos = centerChunk.getChunkPos();
		AdjacentChunkHolder adjacentChunkHolder = new AdjacentChunkHolder(centerChunk);
		
		long startTimeNs = System.nanoTime();
		
		
		// try-finally to handle the stableArray resources
		StableLightPosStack blockLightWorldPosQueue = null;
		StableLightPosStack skyLightWorldPosQueue = null;
		try
		{
			blockLightWorldPosQueue = StableLightPosStack.borrowStableLightPosArray();
			skyLightWorldPosQueue = StableLightPosStack.borrowStableLightPosArray();
			
			
			
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
			for (int chunkIndex = 0; chunkIndex < nearbyChunkList.size(); chunkIndex++) // using iterators in high traffic areas can cause GC issues due to allocating a bunch of iterators, use an indexed for-loop instead
			{
				IChunkWrapper chunk = nearbyChunkList.get(chunkIndex);
				if (chunk != null && requestedAdjacentPositions.contains(chunk.getChunkPos()))
				{
					// remove the newly found position
					requestedAdjacentPositions.remove(chunk.getChunkPos());
					
					// add the adjacent chunk
					adjacentChunkHolder.add(chunk);
					
					
					
					//==================//
					// set block lights //
					//==================//
					
					// get and set the adjacent chunk's initial block lights
					final DhBlockPosMutable relLightBlockPos = PRIMARY_BLOCK_POS_REF.get();
					final DhBlockPosMutable relBlockPos = SECONDARY_BLOCK_POS_REF.get();
					
					ArrayList<DhBlockPos> blockLightPosList = chunk.getWorldBlockLightPosList();
					for (int blockLightIndex = 0; blockLightIndex < blockLightPosList.size(); blockLightIndex++) // using iterators in high traffic areas can cause GC issues due to allocating a bunch of iterators, use an indexed for-loop instead
					{
						DhBlockPos blockLightPos = blockLightPosList.get(blockLightIndex);
						blockLightPos.mutateToChunkRelativePos(relLightBlockPos);
						
						// get the light
						IBlockStateWrapper blockState = chunk.getBlockState(relLightBlockPos);
						int lightValue = blockState.getLightEmission();
						blockLightWorldPosQueue.push(blockLightPos.getX(), blockLightPos.getY(), blockLightPos.getZ(), lightValue);
						
						// set the light
						chunk.setDhBlockLight(relBlockPos.getX(), relBlockPos.getY(), relBlockPos.getZ(), lightValue);
					}
					
					
					
					//================//
					// set sky lights //
					//================//
					
					// get and set the adjacent chunk's initial skylights,
					// if the dimension has skylights
					if (maxSkyLight > 0)
					{
						int maxY = chunk.getMaxNonEmptyHeight();
						int minY = chunk.getMinBuildHeight();
						
						// get the adjacent chunk's sky lights
						for (int relX = 0; relX < LodUtil.CHUNK_WIDTH; relX++) // relative block pos
						{
							for (int relZ = 0; relZ < LodUtil.CHUNK_WIDTH; relZ++)
							{
								// set each pos' sky light all the way down until an opaque block is hit
								for (int y = maxY; y >= minY; y--)
								{
									IBlockStateWrapper block = chunk.getBlockState(relX, y, relZ);
									if (block != null && block.getOpacity() != LodUtil.BLOCK_FULLY_TRANSPARENT)
									{
										// keep moving down until we find a non-transparent block
										break;
									}
									
									
									// add sky light to the queue
									DhBlockPos skyLightPos = new DhBlockPos(chunk.getMinBlockX() + relX, y, chunk.getMinBlockZ() + relZ);
									skyLightWorldPosQueue.push(skyLightPos.getX(), skyLightPos.getY(), skyLightPos.getZ(), maxSkyLight);
									
									// set the chunk's sky light
									skyLightPos.mutateToChunkRelativePos(relBlockPos);
									chunk.setDhSkyLight(relBlockPos.getX(), relBlockPos.getY(), relBlockPos.getZ(), maxSkyLight);
								}
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
			
			// block light
			this.propagateLightPosList(blockLightWorldPosQueue, adjacentChunkHolder,
					(neighbourChunk, relBlockPos) -> neighbourChunk.getDhBlockLight(relBlockPos.getX(), relBlockPos.getY(), relBlockPos.getZ()),
					(neighbourChunk, relBlockPos, newLightValue) -> neighbourChunk.setDhBlockLight(relBlockPos.getX(), relBlockPos.getY(), relBlockPos.getZ(), newLightValue));
			
			// sky light
			this.propagateLightPosList(skyLightWorldPosQueue, adjacentChunkHolder,
					(neighbourChunk, relBlockPos) -> neighbourChunk.getDhSkyLight(relBlockPos.getX(), relBlockPos.getY(), relBlockPos.getZ()),
					(neighbourChunk, relBlockPos, newLightValue) -> neighbourChunk.setDhSkyLight(relBlockPos.getX(), relBlockPos.getY(), relBlockPos.getZ(), newLightValue));
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected lighting issue for center chunk: "+centerChunkPos, e);
		}
		finally
		{
			StableLightPosStack.returnStableLightPosArray(blockLightWorldPosQueue);
			StableLightPosStack.returnStableLightPosArray(skyLightWorldPosQueue);
		}
		
		
		
		centerChunk.setIsDhLightCorrect(true);
		centerChunk.setUseDhLighting(true);
		
		long endTimeNs = System.nanoTime();
		float totalTimeMs = (endTimeNs - startTimeNs) / 1_000_000.0f;
		//LOGGER.trace("Finished generating lighting for chunk: [" + centerChunkPos + "] in ["+totalTimeMs+"] milliseconds");
	}
	
	/** Applies each {@link LightPos} from the queue to the given set of {@link IChunkWrapper}'s. */
	private void propagateLightPosList(
			StableLightPosStack lightPosQueue, AdjacentChunkHolder adjacentChunkHolder,
			IGetLightFunc getLightFunc, ISetLightFunc setLightFunc)
	{
		// these objects are saved so they can be mutated throughout the method,
		// this reduces the number of allocations necessary, reducing GC pressure
		final LightPos lightPos = new LightPos(0, 0, 0, 0);
		final DhBlockPosMutable neighbourBlockPos = PRIMARY_BLOCK_POS_REF.get();
		final DhBlockPosMutable relNeighbourBlockPos = SECONDARY_BLOCK_POS_REF.get();
		
		
		// update each light position
		while (!lightPosQueue.isEmpty())
		{
			// since we don't care about the order the positions are processed,
			// we can grab the last position instead of the first for a slight performance increase (this way the array doesn't need to be shifted over every loop)
			lightPosQueue.popMutate(lightPos);
			
			int lightValue = lightPos.lightValue;
			
			
			// propagate the lighting in each cardinal direction, IE: -x, +x, -y, +y, -z, +z
			for (EDhDirection direction : EDhDirection.CARDINAL_DIRECTIONS) // since this is an array instead of an ArrayList this advanced for-loop shouldn't cause any GC issues
			{
				lightPos.mutateOffset(direction, neighbourBlockPos);
				neighbourBlockPos.mutateToChunkRelativePos(relNeighbourBlockPos);
				
				
				// only continue if the light position is inside one of our chunks
				IChunkWrapper neighbourChunk = adjacentChunkHolder.getByBlockPos(neighbourBlockPos.getX(), neighbourBlockPos.getZ());
				if (neighbourChunk == null)
				{
					// the light pos is outside our generator's range, ignore it
					continue;
				}
				
				if (relNeighbourBlockPos.getY() < neighbourChunk.getMinNonEmptyHeight() || relNeighbourBlockPos.getY() > neighbourChunk.getMaxBuildHeight())
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
					lightPosQueue.push(neighbourBlockPos.getX(), neighbourBlockPos.getY(), neighbourBlockPos.getZ(), targetLevel);
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
	
	private static class LightPos extends DhBlockPosMutable
	{
		public int lightValue;
		
		public LightPos(int x, int y, int z, int lightValue)
		{
			super(x, y, z);
			this.lightValue = lightValue;
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

		/** x, y, z, and lightValue. */
		public static final int INTS_PER_LIGHT_POS = 4;
		
		/**
		 * When tested with a normal 1.20 world James saw a maximum of 36,709 block and 2,355 sky lights,
		 * so 40,000 should be a good starting point that can contain most lighting tasks.
		 */
		private final IntArrayList lightPositions = new IntArrayList(40_000 * INTS_PER_LIGHT_POS);
		
		
		
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
			int subIndex = this.index * INTS_PER_LIGHT_POS;
			if (subIndex < this.lightPositions.size())
			{
				this.lightPositions.set(subIndex, blockX);
				this.lightPositions.set(subIndex + 1, blockY);
				this.lightPositions.set(subIndex + 2, blockZ);
				this.lightPositions.set(subIndex + 3, lightValue);
			}
			else
			{
				// add a new pos
				this.lightPositions.add(blockX);
				this.lightPositions.add(blockY);
				this.lightPositions.add(blockZ);
				this.lightPositions.add(lightValue);
			}
		}
		
		/** mutates the given {@link LightPos} to match the next {@link LightPos} in the queue. */
		public void popMutate(LightPos pos)
		{
			int subIndex = this.index * INTS_PER_LIGHT_POS;
			
			pos.setX(this.lightPositions.getInt(subIndex));
			pos.setY(this.lightPositions.getInt(subIndex + 1));
			pos.setZ(this.lightPositions.getInt(subIndex + 2));
			pos.lightValue = this.lightPositions.getInt(subIndex + 3);
			
			this.index--;
		}
		
		@Override
		public String toString() { return this.index + "/" + (this.lightPositions.size() / INTS_PER_LIGHT_POS); }
		
	}
	
}
