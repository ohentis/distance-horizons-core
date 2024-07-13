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

package com.seibel.distanthorizons.core.api.internal;

import com.seibel.distanthorizons.core.Initializer;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.generation.DhLightingEngine;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo;
import com.seibel.distanthorizons.core.util.TimerUtil;
import com.seibel.distanthorizons.core.util.objects.Pair;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.world.*;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/** Contains code and variables used by both {@link ClientApi} and {@link ServerApi} */
public class SharedApi
{
	public static final SharedApi INSTANCE = new SharedApi();
	
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final Set<DhChunkPos> UPDATING_CHUNK_POS_SET = ConcurrentHashMap.newKeySet();
	/** how many chunks can be queued for updating per thread, used to prevent updates from infinitely pilling up if the user flys around extremely fast */
	private static final int MAX_UPDATING_CHUNK_COUNT_PER_THREAD = 500;
	private static final int MIN_MS_BETWEEN_OVERLOADED_LOG_MESSAGE = 30_000;
	
	private static final Timer CHUNK_UPDATE_TIMER = TimerUtil.CreateTimer("ChunkUpdateTimer");
	
	
	private static AbstractDhWorld currentWorld;
	private static int lastWorldGenTickDelta = 0;
	private static long lastOverloadedLogMessageMsTime = 0;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	private SharedApi() { }
	public static void init() { Initializer.init(); }
	
	
	
	//===============//
	// world methods //
	//===============//
	
	public static EWorldEnvironment getEnvironment() { return (currentWorld == null) ? null : currentWorld.environment; }
	
	public static void setDhWorld(AbstractDhWorld newWorld)
	{
		currentWorld = newWorld;
		
		// starting and stopping the DataRenderTransformer is necessary to prevent attempting to
		// access the MC level at inappropriate times, which can cause exceptions
		if (currentWorld != null)
		{
			ThreadPoolUtil.setupThreadPools();
		}
		else
		{
			ThreadPoolUtil.shutdownThreadPools();
			DebugRenderer.clearRenderables();
			MC_RENDER.clearTargetFrameBuffer();
			// shouldn't be necessary, but if we missed closing one of the connections this should make sure they're all closed
			AbstractDhRepo.closeAllConnections();
			// needs to be closed on world shutdown to clear out un-processed chunks
			UPDATING_CHUNK_POS_SET.clear();
			
			// recommend that the garbage collector cleans up any objects from the old world and thread pools
			System.gc();
		}
	}
	
	public static void worldGenTick(Runnable worldGenRunnable)
	{
		lastWorldGenTickDelta--;
		if (lastWorldGenTickDelta <= 0)
		{
			worldGenRunnable.run();
			lastWorldGenTickDelta = 20;
		}
	}
	
	public static AbstractDhWorld getAbstractDhWorld() { return currentWorld; }
	/** returns null if the {@link SharedApi#currentWorld} isn't a {@link DhClientServerWorld} */
	public static DhClientServerWorld getDhClientServerWorld() { return (currentWorld != null && DhClientServerWorld.class.isInstance(currentWorld)) ? (DhClientServerWorld) currentWorld : null; }
	/** returns null if the {@link SharedApi#currentWorld} isn't a {@link DhClientWorld} or {@link DhClientServerWorld} */
	public static IDhClientWorld getIDhClientWorld() { return (currentWorld != null && IDhClientWorld.class.isInstance(currentWorld)) ? (IDhClientWorld) currentWorld : null; }
	/** returns null if the {@link SharedApi#currentWorld} isn't a {@link DhServerWorld} or {@link DhClientServerWorld} */
	public static IDhServerWorld getIDhServerWorld() { return (currentWorld != null && IDhServerWorld.class.isInstance(currentWorld)) ? (IDhServerWorld) currentWorld : null; }
	
	
	
	//==============//
	// chunk update //
	//==============//
	
	/** 
	 * Used to prevent getting a full chunk from MC if it isn't necessary. <br>
	 * This is important since asking MC for a chunk is slow and may block the render thread.
	 */
	public static boolean isChunkAtBlockPosAlreadyUpdating(int blockPosX, int blockPosZ)
	{ return UPDATING_CHUNK_POS_SET.contains(new DhChunkPos(new DhBlockPos2D(blockPosX, blockPosZ))); }
	
	
	/** handles both block place and break events */
	public void chunkBlockChangedEvent(IChunkWrapper chunk, ILevelWrapper level) { this.applyChunkUpdate(chunk, level, true); }
	
	public void chunkLoadEvent(IChunkWrapper chunk, ILevelWrapper level) { this.applyChunkUpdate(chunk, level, false); }
	public void chunkUnloadEvent(IChunkWrapper chunk, ILevelWrapper level) 
	{
		// temporarily disabled since this was originally incorrectly designated as "chunkSaveEvent"
		// but didn't actually fire on chunk save
		// and generally this is unnecessary and drastically reduces LOD building performance
		// when traveling around the world
		if (false)
		{
			this.applyChunkUpdate(chunk, level, false);
		}
	}
	
	
	public void applyChunkUpdate(IChunkWrapper chunkWrapper, ILevelWrapper level, boolean updateNeighborChunks)
	{
		//========================//
		// world and level checks //
		//========================//
		
		if (chunkWrapper == null)
		{
			// shouldn't happen, but just in case
			return;
		}
		
		AbstractDhWorld dhWorld = SharedApi.getAbstractDhWorld();
		if (dhWorld == null)
		{
			if (level instanceof IClientLevelWrapper)
			{
				// If the client world isn't loaded yet, keep track of which chunks were loaded so we can use them later.
				// This may happen if the client world and client level load events happen out of order
				IClientLevelWrapper clientLevel = (IClientLevelWrapper) level;
				ClientApi.INSTANCE.waitingChunkByClientLevelAndPos.replace(new Pair<>(clientLevel, chunkWrapper.getChunkPos()), chunkWrapper);
			}
			
			return;
		}
		
		// only continue if the level is loaded
		IDhLevel dhLevel = dhWorld.getLevel(level);
		if (dhLevel == null)
		{
			if (level instanceof IClientLevelWrapper)
			{
				// the client level isn't loaded yet
				IClientLevelWrapper clientLevel = (IClientLevelWrapper) level;
				ClientApi.INSTANCE.waitingChunkByClientLevelAndPos.replace(new Pair<>(clientLevel, chunkWrapper.getChunkPos()), chunkWrapper);
			}
			
			return;
		}
		
		
		
		//=====================//
		// task limiting check //
		//=====================//
		
		int currentQueueCount = UPDATING_CHUNK_POS_SET.size();
		int maxQueueCount = MAX_UPDATING_CHUNK_COUNT_PER_THREAD * Config.Client.Advanced.MultiThreading.numberOfLodBuilderThreads.get();
		if (currentQueueCount >= maxQueueCount)
		{
			// The maximum number of chunks are already queued, don't add more.
			// This is done to prevent overloading the system if the user fly's extremely fast and queues too many chunks
			
			long msBetweenLastLog = System.currentTimeMillis() - lastOverloadedLogMessageMsTime;
			if (msBetweenLastLog >= MIN_MS_BETWEEN_OVERLOADED_LOG_MESSAGE)
			{
				lastOverloadedLogMessageMsTime = System.currentTimeMillis();
				
				String message = "Distant Horizons overloaded, too many chunks queued for updating. " +
						"\nThis may result in holes in your LODs. " +
						"\nPlease move through the world slower, decrease your vanilla render distance, slow down your world pre-generator, or increase the Distant Horizons' CPU load config. " +
						"\nMax queue count ["+maxQueueCount+"] (["+MAX_UPDATING_CHUNK_COUNT_PER_THREAD+"] per thread).";
				ClientApi.INSTANCE.showChatMessageNextFrame(message);
				LOGGER.warn(message);
			}
			
			return;
		}
		
		// prevent duplicate update requests
		if (UPDATING_CHUNK_POS_SET.contains(chunkWrapper.getChunkPos()))
		{
			// this chunk is already being updated
			return;
		}
		UPDATING_CHUNK_POS_SET.add(chunkWrapper.getChunkPos());
		
		
		
		//===============================//
		// update the necessary chunk(s) //
		//===============================//
		
		if (!updateNeighborChunks)
		{
			// only update the center chunk
			
			bakeChunkLightingAndSendToLevelAsync(chunkWrapper, null, dhLevel);
		}
		else
		{
			// update the center and any existing neighbour chunks. 
			// this is done so lighting changes are propagated correctly
			
			// get the neighboring chunk list
			ArrayList<IChunkWrapper> neighbourChunkList = new ArrayList<>(9);
			for (int xOffset = -1; xOffset <= 1; xOffset++)
			{
				for (int zOffset = -1; zOffset <= 1; zOffset++)
				{
					if (xOffset == 0 && zOffset == 0)
					{
						// center chunk
						neighbourChunkList.add(chunkWrapper);
					}
					else
					{
						// neighboring chunk
						DhChunkPos neighbourPos = new DhChunkPos(chunkWrapper.getChunkPos().x + xOffset, chunkWrapper.getChunkPos().z + zOffset);
						IChunkWrapper neighbourChunk = dhLevel.getLevelWrapper().tryGetChunk(neighbourPos);
						if (neighbourChunk != null)
						{
							neighbourChunkList.add(neighbourChunk);
						}
					}
				}
			}
			
			// light and send the chunks
			for (IChunkWrapper litChunk : neighbourChunkList)
			{
				bakeChunkLightingAndSendToLevelAsync(litChunk, neighbourChunkList, dhLevel);
			}
		}
	}
	private static void bakeChunkLightingAndSendToLevelAsync(IChunkWrapper chunkWrapper, @Nullable ArrayList<IChunkWrapper> neighbourChunkList, IDhLevel dhLevel)
	{
		// lighting the chunk needs to be done on a separate thread to prevent lagging any of the event threads
		ThreadPoolExecutor executor = ThreadPoolUtil.getLightPopulatorExecutor();
		if (executor == null)
		{
			return;
		}
		
		try
		{
			executor.execute(() ->
			{
				//LOGGER.trace(chunkWrapper.getChunkPos() + " " + executor.getActiveCount() + " / " + executor.getQueue().size() + " - " + executor.getCompletedTaskCount());
				
				try
				{
					// check if this chunk has been converted into an LOD already
					int oldChunkHash = dhLevel.getChunkHash(chunkWrapper.getChunkPos()); // shouldn't happen on the render thread since it may take a few moments to run
					int newChunkHash = chunkWrapper.getBlockBiomeHashCode();
					if (oldChunkHash == newChunkHash)
					{
						// if the chunk hashes are the same then we don't need to bother with lighting the chunk
						// or creating/updating the LODs
						//LOGGER.info("skipping: "+chunkWrapper.getChunkPos()+" "+newChunkHash);
						return;
					}
					else
					{
						//LOGGER.info("g: "+chunkWrapper.getChunkPos()+" "+newChunkHash);
					}
					
					
					// having a list of the nearby chunks is needed for lighting and beacon generation
					ArrayList<IChunkWrapper> nearbyChunkList;
					if (neighbourChunkList != null)
					{
						nearbyChunkList = neighbourChunkList;
					}
					else
					{
						nearbyChunkList = new ArrayList<>(1);
						nearbyChunkList.add(chunkWrapper);
					}
					
					
					// Save or populate the chunk wrapper's lighting
					// this is done so we don't have to worry about MC unloading the lighting data for this chunk
					boolean onlyUseDhLighting = Config.Client.Advanced.LodBuilding.onlyUseDhLightingEngine.get();
					if (!onlyUseDhLighting && chunkWrapper.isLightCorrect())
					{
						try
						{
							// If MC's lighting engine isn't thread safe this may cause the server thread to lag
							chunkWrapper.bakeDhLightingUsingMcLightingEngine();
						}
						catch (IllegalStateException e)
						{
							LOGGER.warn("Chunk light baking error: " + e.getMessage(), e);
						}
					}
					else
					{
						// generate the chunk's lighting, using neighboring chunks if present
						DhLightingEngine.INSTANCE.lightChunk(chunkWrapper, nearbyChunkList, dhLevel.hasSkyLight() ? 15 : 0);
					}
					
					
					
					// get this chunk's active beacons
					List<BeaconBeamDTO> beaconBeamList = chunkWrapper.getAllActiveBeacons(nearbyChunkList);
					dhLevel.setBeaconBeamsForChunk(chunkWrapper.getChunkPos(), beaconBeamList);
					
					
					dhLevel.updateChunkAsync(chunkWrapper);
					dhLevel.setChunkHash(chunkWrapper.getChunkPos(), newChunkHash);
				}
				catch (Exception e)
				{
					LOGGER.error("Unexpected error when updating chunk at pos: [" + chunkWrapper.getChunkPos() + "]", e);
				}
				finally
				{
					// the LOD chunk has finished being updated
					int updateTimeoutInSec = Config.Client.Advanced.LodBuilding.minTimeBetweenChunkUpdatesInSeconds.get();
					if (updateTimeoutInSec != 0)
					{
						// prevent updating this chunk again until the timeout finishes
						CHUNK_UPDATE_TIMER.schedule(new TimerTask()
						{
							@Override
							public void run() { UPDATING_CHUNK_POS_SET.remove(chunkWrapper.getChunkPos()); }
						}, updateTimeoutInSec * 1000L);
					}
					else
					{
						// instantly allow this chunk to be updated again
						UPDATING_CHUNK_POS_SET.remove(chunkWrapper.getChunkPos());
					}
				}
			});
		}
		catch (RejectedExecutionException ignore) { /* the executor was shut down, it should be back up shortly and able to accept new jobs */ }
	}
	
	
	
	//=========//
	// F3 Menu //
	//=========//
	
	public String getDebugMenuString()
	{
		int maxUpdateCount = MAX_UPDATING_CHUNK_COUNT_PER_THREAD * Config.Client.Advanced.MultiThreading.numberOfLodBuilderThreads.get();
		String updatingCountStr = F3Screen.NUMBER_FORMAT.format(UPDATING_CHUNK_POS_SET.size());
		String maxUpdateCountStr = F3Screen.NUMBER_FORMAT.format(maxUpdateCount);
		return "Queued chunk updates: "+updatingCountStr+" / "+maxUpdateCountStr;
	}
	
	
}
