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
import com.seibel.distanthorizons.core.network.ScopedNetworkEventSource;
import com.seibel.distanthorizons.core.network.netty.NettyServer;
import com.seibel.distanthorizons.core.network.exceptions.RequestRejectedException;
import com.seibel.distanthorizons.core.network.messages.netty.base.CancelMessage;
import com.seibel.distanthorizons.core.network.messages.netty.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.netty.fullData.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.network.messages.netty.fullData.generation.GenTaskPriorityRequestMessage;
import com.seibel.distanthorizons.core.network.messages.netty.fullData.generation.GenTaskPriorityResponseMessage;
import com.seibel.distanthorizons.core.network.messages.netty.fullData.FullDataPartialUpdateMessage;
import com.seibel.distanthorizons.core.network.netty.NettyMessage;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.coreapi.util.math.Vec3d;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CompletableFuture;

import javax.annotation.CheckForNull;
import java.util.Map;
import java.util.concurrent.*;

public class DhServerLevel extends AbstractDhLevel implements IDhServerLevel
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	public final ServerLevelModule serverside;
	private final IServerLevelWrapper serverLevelWrapper;
	
	private final RemotePlayerConnectionHandler remotePlayerConnectionHandler;
	private final ScopedNetworkEventSource<NettyServer, NettyMessage> eventSource;
	
	private final ConcurrentLinkedQueue<IServerPlayerWrapper> worldGenLoopingQueue = new ConcurrentLinkedQueue<>();
	private final ConcurrentMap<DhSectionPos, IncompleteDataSourceEntry> incompleteDataSources = new ConcurrentHashMap<>();
	private final ConcurrentMap<Long, IncompleteDataSourceEntry> fullDataRequests = new ConcurrentHashMap<>();
	
	public DhServerLevel(AbstractSaveStructure saveStructure, IServerLevelWrapper serverLevelWrapper, RemotePlayerConnectionHandler remotePlayerConnectionHandler)
	{
		if (saveStructure.getFullDataFolder(serverLevelWrapper).mkdirs())
		{
			LOGGER.warn("unable to create data folder.");
		}
		this.serverLevelWrapper = serverLevelWrapper;
		this.serverside = new ServerLevelModule(this, saveStructure);
		LOGGER.info("Started DHLevel for {} with saves at {}", serverLevelWrapper, saveStructure);
	
		this.remotePlayerConnectionHandler = remotePlayerConnectionHandler;
		this.eventSource = new ScopedNetworkEventSource<>(remotePlayerConnectionHandler.server());
		this.registerNetworkHandlers();
	}
	
	private void registerNetworkHandlers()
	{
		this.eventSource.registerHandler(FullDataSourceRequestMessage.class, this.remotePlayerConnectionHandler.currentLevelOnly(this, (msg, serverPlayerState) ->
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
					IncompleteDataSourceEntry entry = this.incompleteDataSources.computeIfAbsent(msg.sectionPos, pos ->
					{
						IncompleteDataSourceEntry newEntry = new IncompleteDataSourceEntry();
						this.trySetGeneratedDataSourceToEntry(newEntry, pos);
						return newEntry;
					});
					// If this fails, current entry is being drained and need to create another one
					if (entry.requestCollectionSemaphore.tryAcquire())
					{
						this.fullDataRequests.put(msg.futureId, entry);
						entry.requestMessages.put(msg.futureId, msg);
						entry.requestCollectionSemaphore.release();
						break;
					}
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
				if (serverTimestamp == null || serverTimestamp < msg.clientTimestamp)
				{
					rateLimiterSet.loginDataSyncRCLimiter.release();
					msg.sendResponse(new FullDataSourceResponseMessage(null));
					return;
				}
				
				this.serverside.fullDataFileHandler.getAsync(msg.sectionPos).thenAccept(fullDataSource ->
				{
					rateLimiterSet.loginDataSyncRCLimiter.release();
					msg.sendResponse(new FullDataSourceResponseMessage(fullDataSource));
				});
			}
		}));
		
		this.eventSource.registerHandler(GenTaskPriorityRequestMessage.class, this.remotePlayerConnectionHandler.currentLevelOnly(this, (msg, serverPlayerState) ->
		{
			msg.sendResponse(new GenTaskPriorityResponseMessage(
					this.serverside.fullDataFileHandler.getLoadStates(msg.posList.stream()
							.limit(serverPlayerState.getRateLimiterSet(this).genTaskPriorityRequestRateLimiter.acquireOrDrain(msg.posList.size()))
							::iterator)
			));
		}));
		
		this.eventSource.registerHandler(CancelMessage.class, msg ->
		{
			IncompleteDataSourceEntry entry = this.fullDataRequests.remove(msg.futureId);
			if (entry == null)
			{
				return;
			}
			FullDataSourceRequestMessage requestMessage = entry.requestMessages.remove(msg.futureId);
			
			ServerPlayerState serverPlayerState = this.remotePlayerConnectionHandler.getConnectedPlayer(msg);
			if (serverPlayerState != null)
			{
				serverPlayerState.getRateLimiterSet(this).fullDataRequestConcurrencyLimiter.release();
			}
			
			entry.requestCollectionSemaphore.acquireUninterruptibly(Short.MAX_VALUE);
			if (entry.requestMessages.isEmpty())
			{
				this.incompleteDataSources.remove(requestMessage.sectionPos);
				this.serverside.fullDataFileHandler.removeRetrievalRequestIf(pos -> pos == requestMessage.sectionPos);
			}
			
			entry.requestCollectionSemaphore.release(Short.MAX_VALUE);
		});
	}
	
	public void addPlayer(IServerPlayerWrapper serverPlayer)
	{
		this.worldGenLoopingQueue.add(serverPlayer);
	}
	
	public void removePlayer(IServerPlayerWrapper serverPlayer)
	{
		this.worldGenLoopingQueue.remove(serverPlayer);
	}
	
	@Override
	public void serverTick()
	{
		this.chunkToLodBuilder.tick();
		
		// Send finished data source requests
		for (Map.Entry<DhSectionPos, IncompleteDataSourceEntry> mapEntry : this.incompleteDataSources.entrySet())
		{
			IncompleteDataSourceEntry entry = mapEntry.getValue();
			
			if (entry.fullDataSource == null)
			{
				continue;
			}
			
			this.incompleteDataSources.remove(mapEntry.getKey());
			
			// This semaphore is intentionally acquired forever
			entry.requestCollectionSemaphore.acquireUninterruptibly(Short.MAX_VALUE);
			
			for (FullDataSourceRequestMessage msg : entry.requestMessages.values())
			{
				this.fullDataRequests.remove(msg.futureId);
				
				ServerPlayerState serverPlayerState = this.remotePlayerConnectionHandler.getConnectedPlayer(msg);
				if (serverPlayerState == null)
				{
					continue;
				}
				
				serverPlayerState.getRateLimiterSet(this).fullDataRequestConcurrencyLimiter.release();
				msg.sendResponse(new FullDataSourceResponseMessage(entry.fullDataSource));
			}
		}
	}
	
	@Override
	public CompletableFuture<Void> updateDataSourcesAsync(FullDataSourceV2 data)
	{
		if (!Config.Client.Advanced.Multiplayer.ServerNetworking.enableRealTimeUpdates.get())
		{
			this.getFullDataProvider().updateDataSourceAsync(data);
		}
		
		for (ServerPlayerState serverPlayerState : this.remotePlayerConnectionHandler.getConnectedPlayers())
		{
			if (!serverPlayerState.config.isRealTimeUpdatesEnabled())
			{
				continue;
			}
			
			Vec3d playerPosition = serverPlayerState.serverPlayer.getPosition();
			double distanceFromPlayer = data.getPos().getManhattanBlockDistance(new DhBlockPos2D((int) playerPosition.x, (int) playerPosition.z));
			if (distanceFromPlayer > serverPlayerState.serverPlayer.getViewDistance() &&
					distanceFromPlayer < serverPlayerState.config.getRenderDistanceRadius())
			{
				serverPlayerState.connection.sendMessage(new FullDataPartialUpdateMessage(this.serverLevelWrapper, data));
			}
		}
		
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
			IServerPlayerWrapper firstPlayer = this.worldGenLoopingQueue.peek();
			if (firstPlayer == null)
			{
				return;
			}
			
			// Put first player in back before removing from front, so it can be removed by other thread without blocking
			// - if it gets removed, remove() below will remove the item we just put instead
			this.worldGenLoopingQueue.add(firstPlayer);
			this.worldGenLoopingQueue.remove(firstPlayer);
			
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
	
	private void trySetGeneratedDataSourceToEntry(IncompleteDataSourceEntry entry, DhSectionPos pos)
	{
		this.serverside.fullDataFileHandler.getAsync(pos).thenAccept(fullDataSource -> {
			if (this.serverside.fullDataFileHandler.isFullyGenerated(fullDataSource.columnGenerationSteps))
			{
				entry.fullDataSource = fullDataSource;
			}
		});
	}
	
	@Override
	public void onWorldGenTaskComplete(DhSectionPos pos)
	{
		IncompleteDataSourceEntry entry = this.incompleteDataSources.get(pos);
		if (entry == null)
		{
			return;
		}
		
		this.trySetGeneratedDataSourceToEntry(entry, pos);
	}
	
	private static class IncompleteDataSourceEntry
	{
		@CheckForNull
		public FullDataSourceV2 fullDataSource;
		public final ConcurrentMap<Long, FullDataSourceRequestMessage> requestMessages = new ConcurrentHashMap<>();
		public final Semaphore requestCollectionSemaphore = new Semaphore(Short.MAX_VALUE, true);
	}
	
}