package com.seibel.distanthorizons.core.generation;

import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

/**
 * This logic was roughly based on
 * <a href="https://github.com/PaperMC/Starlight/blob/acc8ed9634bbe27ec68e8842e420948bfa9707e7/TECHNICAL_DETAILS.md">Starlight's technical documentation</a>
 * although there were some changes due to how our lighting engine works in comparison to Minecraft's.
 */
public class DhLightingEngine
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	public static final DhLightingEngine INSTANCE = new DhLightingEngine();
	
	
	private DhLightingEngine() {  }
	
	
	
	/**
	 * Note: depending on the implementation of {@link IChunkWrapper#setDhBlockLight(int, int, int, int)} and {@link IChunkWrapper#setDhSkyLight(int, int, int, int)}
	 * the light values may be stored in the wrapper itself instead of the wrapped chunk object.
	 * If that is the case unwrapping the chunk will undo any work this method did.
	 * 
	 * @param centerChunk the chunk we want to apply lighting to
	 * @param nearbyChunkList should also contain centerChunk
	 * @param maxSkyLight should be a value between 0 and 15
	 */
	public void lightChunks(IChunkWrapper centerChunk, List<IChunkWrapper> nearbyChunkList, int maxSkyLight)
	{
		DhChunkPos centerChunkPos = centerChunk.getChunkPos();
		
		HashMap<DhChunkPos, IChunkWrapper> chunksByChunkPos = new HashMap<>(9);
		LinkedList<LightPos> blockLightPosQueue = new LinkedList<>();
		LinkedList<LightPos> skyLightPosQueue = new LinkedList<>();
		
		// generate the list of chunk pos we need,
		// currently a 3x3 grid
		HashSet<DhChunkPos> requestedAdjacentPositions = new HashSet<>(9);
		for (int xOffset = -1; xOffset <= 1; xOffset++)
		{
			for (int zOffset = -1; zOffset <= 1; zOffset++)
			{
				DhChunkPos adjacentPos = new DhChunkPos(centerChunkPos.x+xOffset, centerChunkPos.z+zOffset);
				requestedAdjacentPositions.add(adjacentPos);
			}	
		}
		
		
		// find all adjacent chunks
		// and get any necessary info from them
		for (IChunkWrapper chunk : nearbyChunkList)
		{
			if (chunk != null && requestedAdjacentPositions.contains(chunk.getChunkPos()))
			{
				// remove the newly found position
				requestedAdjacentPositions.remove(chunk.getChunkPos());
				
				// add the adjacent chunk
				chunksByChunkPos.put(chunk.getChunkPos(), chunk);
				
				
				
				// get and set the adjacent chunk's initial block lights
				List<DhBlockPos> blockLightPosList = chunk.getBlockLightPosList();
				for (DhBlockPos blockLightPos : blockLightPosList)
				{
					// get the light
					DhBlockPos relLightBlockPos = blockLightPos.convertToChunkRelativePos();
					IBlockStateWrapper blockState = chunk.getBlockState(relLightBlockPos);
					int lightValue = blockState.getLightEmission();
					blockLightPosQueue.add(new LightPos(blockLightPos, lightValue));
					
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
							skyLightPosQueue.add(new LightPos(skyLightPos, maxSkyLight));
							
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
		if (chunksByChunkPos.size() == 0)
		{
			LOGGER.warn("Attempted to generate lighting for position ["+centerChunkPos+"], but neither that chunk nor any adjacent chunks were found. No chunk lighting was performed.");
			return;
		}
		
		
		
		// block light
		this.propagateLightPosList(blockLightPosQueue, chunksByChunkPos,
				(neighbourChunk, relBlockPos) -> neighbourChunk.getDhBlockLight(relBlockPos.x, relBlockPos.y, relBlockPos.z),
				(neighbourChunk, relBlockPos, newLightValue) -> neighbourChunk.setDhBlockLight(relBlockPos.x, relBlockPos.y, relBlockPos.z, newLightValue));
		
		// sky light
		this.propagateLightPosList(skyLightPosQueue, chunksByChunkPos,
				(neighbourChunk, relBlockPos) -> neighbourChunk.getDhSkyLight(relBlockPos.x, relBlockPos.y, relBlockPos.z),
				(neighbourChunk, relBlockPos, newLightValue) -> neighbourChunk.setDhSkyLight(relBlockPos.x, relBlockPos.y, relBlockPos.z, newLightValue));
		
		
		LOGGER.trace("Finished generating lighting for chunk: ["+centerChunkPos+"]");
	}
	
	/** Applies each {@link LightPos} from the queue to the given set of {@link IChunkWrapper}'s. */
	private void propagateLightPosList(
			LinkedList<LightPos> lightPosQueue, HashMap<DhChunkPos, IChunkWrapper> chunksByChunkPos,
			IGetLightFunc getLightFunc, ISetLightFunc setLightFunc)
	{
		// update each light position
		while (!lightPosQueue.isEmpty())
		{
			LightPos lightPos = lightPosQueue.poll();
			DhBlockPos pos = lightPos.pos;
			int lightValue = lightPos.lightValue;
			
			// propagate the lighting in each cardinal direction, IE: -x, +x, -y, +y, -z, +z
			for (EDhDirection direction : EDhDirection.CARDINAL_DIRECTIONS)
			{
				DhBlockPos neighbourBlockPos = pos.offset(direction);
				DhChunkPos neighbourChunkPos = new DhChunkPos(neighbourBlockPos);
				// converting the block pos into a relative position is necessary for accessing the light values in the chunk
				DhBlockPos relNeighbourBlockPos = neighbourBlockPos.convertToChunkRelativePos();
				
				
				// only continue if the light position is inside one of our chunks
				IChunkWrapper neighbourChunk = chunksByChunkPos.get(neighbourChunkPos);
				if (neighbourChunk == null)
				{
					// the light pos is outside our generator's range, ignore it
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
					lightPosQueue.add(new LightPos(neighbourBlockPos, targetLevel));
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
		public final int lightValue;
		
		public LightPos(DhBlockPos pos, int lightValue)
		{
			this.pos = pos;
			this.lightValue = lightValue;
		}
	}
	
	
}
