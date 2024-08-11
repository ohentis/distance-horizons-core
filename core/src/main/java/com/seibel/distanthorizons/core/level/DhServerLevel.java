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

package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerState;
import com.seibel.distanthorizons.core.multiplayer.server.RemotePlayerConnectionHandler;
import com.seibel.distanthorizons.core.network.exceptions.InvalidLevelException;
import com.seibel.distanthorizons.core.network.exceptions.RequestRejectedException;
import com.seibel.distanthorizons.core.network.messages.ILevelRelatedMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataPayload;
import com.seibel.distanthorizons.core.network.messages.requests.CancelMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataPartialUpdateMessage;
import com.seibel.distanthorizons.core.network.messages.NetworkMessage;
import com.seibel.distanthorizons.core.network.messages.TrackableMessage;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.render.renderer.generic.GenericObjectRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.core.util.math.Vec3d;
import org.apache.logging.log4j.Logger;

import java.text.MessageFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.CheckForNull;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class DhServerLevel extends AbstractDhLevel implements IDhServerLevel
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	public static final int FULL_DATA_CHUNK_SIZE = 1048000; // 576 bytes left for other contents
	
	public final ServerLevelModule serverside;
	private final IServerLevelWrapper serverLevelWrapper;
	
	private final RemotePlayerConnectionHandler remotePlayerConnectionHandler;
	
	/**
	 * This queue is used for ensuring fair generation speed for each player. <br>
	 * Every tick the first player gets used for centering generation, and then is immediately moved into the back of the queue. <br>
	 * TODO only add players that actually have something to generate
	 */
	private final ConcurrentLinkedQueue<IServerPlayerWrapper> worldGenPlayerCenteringQueue = new ConcurrentLinkedQueue<>();
	
	private final ConcurrentMap<Long, DataSourceRequestGroup> requestGroupsByPos = new ConcurrentHashMap<>();
	private final ConcurrentMap<Long, DataSourceRequestGroup> requestGroupsByFutureId = new ConcurrentHashMap<>();
	
	
	//=============//
	// constructor //
	//=============//
	
	public DhServerLevel(AbstractSaveStructure saveStructure, IServerLevelWrapper serverLevelWrapper, RemotePlayerConnectionHandler remotePlayerConnectionHandler)
	{
		if (saveStructure.getFullDataFolder(serverLevelWrapper).mkdirs())
		{
			LOGGER.warn("unable to create data folder.");
		}
		this.serverLevelWrapper = serverLevelWrapper;
		this.serverside = new ServerLevelModule(this, saveStructure);
		this.createAndSetSupportingRepos(this.serverside.fullDataFileHandler.repo.databaseFile);
		this.runRepoReliantSetup();
		
		LOGGER.info("Started DHLevel for {} with saves at {}", serverLevelWrapper, saveStructure);
	
		this.remotePlayerConnectionHandler = remotePlayerConnectionHandler;
	}
	
	public void registerNetworkHandlers(ServerPlayerState serverPlayerState)
	{
		serverPlayerState.session.registerHandler(FullDataSourceRequestMessage.class, this.currentLevelOnly(msg ->
		{
			ServerPlayerState.RateLimiterSet rateLimiterSet = serverPlayerState.getRateLimiterSet(this);
			
			if (msg.clientTimestamp == null)
			{
				// Normal generation
				
				if (!serverPlayerState.config.isDistantGenerationEnabled())
				{
					msg.sendResponse(new RequestRejectedException("Operation is disabled from config."));
					return;
				}
				
				if (!rateLimiterSet.fullDataRequestConcurrencyLimiter.tryAcquire(msg))
				{
					return;
				}
				
				while (true)
				{
					DataSourceRequestGroup requestGroup = this.requestGroupsByPos.computeIfAbsent(msg.sectionPos, pos ->
					{
						DataSourceRequestGroup newGroup = new DataSourceRequestGroup();
						this.tryFulfillDataSourceRequestGroup(newGroup, pos);
						return newGroup;
					});
					
					// If this fails, loop until either permit is acquired or group is removed to create another one
					if (!requestGroup.requestAddSemaphore.tryAcquire())
					{
						Thread.yield();
						continue;
					}
					
					this.requestGroupsByFutureId.put(msg.futureId, requestGroup);
					requestGroup.requestMessages.put(msg.futureId, msg);
					requestGroup.requestAddSemaphore.release();
					break;
				}
			}
			else
			{
				// Sync only
				
				if (!serverPlayerState.config.isLoginDataSyncEnabled())
				{
					msg.sendResponse(new RequestRejectedException("Operation is disabled from config."));
					return;
				}
				
				if (!rateLimiterSet.loginDataSyncRCLimiter.tryAcquire(msg))
				{
					return;
				}
				
				Long serverTimestamp = this.serverside.fullDataFileHandler.getTimestampForPos(msg.sectionPos);
				if (serverTimestamp == null || serverTimestamp <= msg.clientTimestamp)
				{
					rateLimiterSet.loginDataSyncRCLimiter.release();
					msg.sendResponse(new FullDataSourceResponseMessage(null));
					return;
				}
				
				ThreadPoolExecutor executor = ThreadPoolUtil.getNetworkCompressionExecutor();
				if (executor == null)
				{
					LOGGER.warn("Unable to send FullDataSourceResponseMessage - getNetworkCompressionExecutor() is null");
					return;
				}
				this.serverside.fullDataFileHandler.getAsync(msg.sectionPos).thenAcceptAsync(fullDataSource ->
				{
					rateLimiterSet.loginDataSyncRCLimiter.release();
					
					FullDataPayload payload = new FullDataPayload(fullDataSource);
					payload.acceptInChunkMessages(FULL_DATA_CHUNK_SIZE, msg.getSession()::sendMessage);
					msg.sendResponse(new FullDataSourceResponseMessage(payload));
				}, executor);
			}
		}));
		
		serverPlayerState.session.registerHandler(CancelMessage.class, msg ->
		{
			DataSourceRequestGroup requestGroup = this.requestGroupsByFutureId.remove(msg.futureId);
			if (requestGroup == null)
			{
				return;
			}
			
			// If this fails, group is being removed and completing cancellation is not necessary
			if (requestGroup.requestRemoveSemaphore.tryAcquire())
			{
				// Prevent adding requests in case the group will be removed by this cancellation
				requestGroup.requestAddSemaphore.acquireUninterruptibly(Short.MAX_VALUE);
				requestGroup.requestRemoveSemaphore.release();
				
				serverPlayerState.getRateLimiterSet(this).fullDataRequestConcurrencyLimiter.release();
				
				FullDataSourceRequestMessage requestMessage = requestGroup.requestMessages.remove(msg.futureId);
				if (requestGroup.requestMessages.isEmpty())
				{
					this.requestGroupsByPos.remove(requestMessage.sectionPos);
					this.serverside.fullDataFileHandler.removeRetrievalRequestIf(pos -> pos == requestMessage.sectionPos);
				}
				else
				{
					requestGroup.requestAddSemaphore.release(Short.MAX_VALUE);
				}
			}
		});
	}
	
	
	
	public <T extends NetworkMessage> Consumer<T> currentLevelOnly(Consumer<T> next)
	{
		return msg ->
		{
			LodUtil.assertTrue(msg instanceof ILevelRelatedMessage, MessageFormat.format("Received message does not implement {0}: {1}", ILevelRelatedMessage.class.getSimpleName(), msg.getClass().getSimpleName()));
			
			// Handle only in requested dimension
			if (!((ILevelRelatedMessage) msg).isSameLevelAs(this.getServerLevelWrapper()))
			{
				return;
			}
			
			// If player is not in this dimension and handling multiple dimensions at once is not allowed
			assert msg.getSession().serverPlayer != null;
			if (msg.getSession().serverPlayer.getLevel() != this.getLevelWrapper())
			{
				// If the message can be replied to - reply with error, otherwise just ignore
				if (msg instanceof TrackableMessage)
				{
					((TrackableMessage) msg).sendResponse(new InvalidLevelException(MessageFormat.format(
							"Generation not allowed. Requested dimension: {0}, player dimension: {1}, handler dimension: {2}",
							((ILevelRelatedMessage) msg).getLevelName(),
							msg.getSession().serverPlayer.getLevel().getDimensionName(),
							this.getLevelWrapper().getDimensionName()
					)));
				}
				
				return;
			}
			
			next.accept(msg);
		};
	}
	
	public void addPlayer(IServerPlayerWrapper serverPlayer)
	{
		this.worldGenPlayerCenteringQueue.add(serverPlayer);
	}
	
	//=========//
	// methods //
	//=========//
	
	public void removePlayer(IServerPlayerWrapper serverPlayer)
	{
		this.worldGenPlayerCenteringQueue.remove(serverPlayer);
	}
	
	@Override
	public void serverTick()
	{
		// Send finished data source requests
		for (Map.Entry<Long, DataSourceRequestGroup> entry : this.requestGroupsByPos.entrySet())
		{
			DataSourceRequestGroup requestGroup = entry.getValue();
			
			if (requestGroup.fullDataSource == null)
			{
				continue;
			}
			
			// Make this group unavailable for adding into
			this.requestGroupsByPos.remove(entry.getKey());
			requestGroup.requestRemoveSemaphore.acquireUninterruptibly(Short.MAX_VALUE);
			requestGroup.requestAddSemaphore.acquireUninterruptibly(Short.MAX_VALUE);
			
			ThreadPoolExecutor executor = ThreadPoolUtil.getNetworkCompressionExecutor();
			if (executor == null)
			{
				LOGGER.warn("Unable to send FullDataSourceResponseMessage - getNetworkCompressionExecutor() is null");
				continue;
			}
			CompletableFuture.runAsync(() ->
			{
				try (FullDataPayload payload = new FullDataPayload(requestGroup.fullDataSource))
				{
					for (FullDataSourceRequestMessage msg : requestGroup.requestMessages.values())
					{
						this.requestGroupsByFutureId.remove(msg.futureId);
						
						ServerPlayerState serverPlayerState = this.remotePlayerConnectionHandler.getConnectedPlayer(msg.serverPlayer());
						if (serverPlayerState == null)
						{
							continue;
						}
						
						serverPlayerState.getRateLimiterSet(this).fullDataRequestConcurrencyLimiter.release();
						payload.acceptInChunkMessages(FULL_DATA_CHUNK_SIZE, msg.getSession()::sendMessage);
						msg.sendResponse(new FullDataSourceResponseMessage(payload.retain()));
					}
				}
			}, executor);
		}
	}
	
	@Override
	public CompletableFuture<Void> updateDataSourcesAsync(FullDataSourceV2 data)
	{
		if (!Config.Client.Advanced.Multiplayer.ServerNetworking.enableRealTimeUpdates.get())
		{
			return this.getFullDataProvider().updateDataSourceAsync(data);
		}
		
		ThreadPoolExecutor executor = ThreadPoolUtil.getNetworkCompressionExecutor();
		if (executor == null)
		{
			LOGGER.warn("Unable to send FullDataPartialUpdateMessage - getNetworkCompressionExecutor() is null");
			return this.getFullDataProvider().updateDataSourceAsync(data);
		}
		CompletableFuture.runAsync(() ->
		{
			try (FullDataPayload payload = new FullDataPayload(data))
			{
				for (ServerPlayerState serverPlayerState : this.remotePlayerConnectionHandler.getConnectedPlayers())
				{
					if (serverPlayerState.serverPlayer().getLevel() != this.serverLevelWrapper)
					{
						continue;
					}
					
					if (!serverPlayerState.config.isRealTimeUpdatesEnabled())
					{
						continue;
					}
					
					Vec3d playerPosition = serverPlayerState.serverPlayer().getPosition();
					int distanceFromPlayer = DhSectionPos.getManhattanBlockDistance(data.getPos(), new DhBlockPos2D((int) playerPosition.x, (int) playerPosition.z)) / 16;
					if (distanceFromPlayer >= serverPlayerState.serverPlayer().getViewDistance() &&
							distanceFromPlayer <= serverPlayerState.config.getRenderDistanceRadius())
					{
						payload.acceptInChunkMessages(FULL_DATA_CHUNK_SIZE, serverPlayerState.session::sendMessage);
						serverPlayerState.session.sendMessage(new FullDataPartialUpdateMessage(this.serverLevelWrapper, payload.retain()));
					}
				}
			}
		}, executor);
		
		
		return this.getFullDataProvider().updateDataSourceAsync(data);
	}
	
	@Override
	public int getMinY()
	{
		return this.getLevelWrapper().getMinHeight();
	}
	
	@Override
	public void close()
	{
		super.close();
		this.serverside.close();
		LOGGER.info("Closed DHLevel for {}", this.getLevelWrapper());
	}
	
	@Override
	public void doWorldGen()
	{
		boolean shouldDoWorldGen = true; //todo;
		boolean isWorldGenRunning = this.serverside.worldGenModule.isWorldGenRunning();
		if (shouldDoWorldGen && !isWorldGenRunning)
		{
			// start world gen
			this.serverside.worldGenModule.startWorldGen(this.serverside.fullDataFileHandler, new ServerLevelModule.WorldGenState(this));
		}
		else if (!shouldDoWorldGen && isWorldGenRunning)
		{
			// stop world gen
			this.serverside.worldGenModule.stopWorldGen(this.serverside.fullDataFileHandler);
		}
		
		if (this.serverside.worldGenModule.isWorldGenRunning())
		{
			IServerPlayerWrapper firstPlayer = this.worldGenPlayerCenteringQueue.peek();
			if (firstPlayer == null)
			{
				return;
			}
			
			// Put first player in back before removing from front, so it can be removed by other thread without blocking
			// - if it gets removed, remove() below will remove the item we just put instead
			this.worldGenPlayerCenteringQueue.add(firstPlayer);
			this.worldGenPlayerCenteringQueue.remove(firstPlayer);
			
			Vec3d position = firstPlayer.getPosition();
			this.serverside.worldGenModule.worldGenTick(new DhBlockPos2D((int) position.x, (int) position.z));
		}
	}
	
	@Override
	public IServerLevelWrapper getServerLevelWrapper()
	{
		return this.serverLevelWrapper;
	}
	
	@Override
	public ILevelWrapper getLevelWrapper()
	{
		return this.getServerLevelWrapper();
	}
	
	@Override
	public FullDataSourceProviderV2 getFullDataProvider()
	{
		return this.serverside.fullDataFileHandler;
	}
	
	@Override
	public AbstractSaveStructure getSaveStructure()
	{
		return this.serverside.saveStructure;
	}
	
	@Override
	public boolean hasSkyLight() { return this.serverLevelWrapper.hasSkyLight(); }
	
	private void tryFulfillDataSourceRequestGroup(DataSourceRequestGroup requestGroup, long pos)
	{
		this.serverside.fullDataFileHandler.getAsync(pos).thenAccept(fullDataSource -> {
			if (this.serverside.fullDataFileHandler.isFullyGenerated(fullDataSource.columnGenerationSteps))
			{
				requestGroup.fullDataSource = fullDataSource;
			}
			else
			{
				this.serverside.fullDataFileHandler.queuePositionForRetrieval(pos);
			}
		});
	}
	
	@Override
	public void onWorldGenTaskComplete(long pos)
	{
		DataSourceRequestGroup requestGroup = this.requestGroupsByPos.get(pos);
		if (requestGroup != null)
		{
			this.tryFulfillDataSourceRequestGroup(requestGroup, pos);
		}
	}
	
	@Override
	public GenericObjectRenderer getGenericRenderer() 
	{ 
		// server-only levels don't support rendering
		return null; 
	}
	@Override
	public RenderBufferHandler getRenderBufferHandler()
	{ 
		// server-only levels don't support rendering
		return null; 
	}
	
	
	//===========//
	// debugging //
	//===========//
	
	@Override
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		String dimName = this.serverLevelWrapper.getDimensionName();
		messageList.add("["+dimName+"]");
	}
	
	private static class DataSourceRequestGroup
	{
		public final ConcurrentMap<Long, FullDataSourceRequestMessage> requestMessages = new ConcurrentHashMap<>();

		@CheckForNull
		public FullDataSourceV2 fullDataSource;
		
		// Maybe there's a better way to do synchronization, but this should suffice
		// Why not something like ReentrantReadWriteLock: locks should not be bound to threads
		public final Semaphore requestAddSemaphore = new Semaphore(Short.MAX_VALUE, true);
		public final Semaphore requestRemoveSemaphore = new Semaphore(Short.MAX_VALUE, true);
	}
	
}