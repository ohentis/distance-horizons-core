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

import com.seibel.distanthorizons.core.config.AppliedConfigState;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IIncompleteFullDataSource;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataMetaFile;
import com.seibel.distanthorizons.core.file.fullDatafile.IFullDataSourceProvider;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.multiplayer.ServerPlayerState;
import com.seibel.distanthorizons.core.multiplayer.RemotePlayerConnectionHandler;
import com.seibel.distanthorizons.core.network.ScopedNetworkEventSource;
import com.seibel.distanthorizons.core.network.NetworkServer;
import com.seibel.distanthorizons.core.network.exceptions.InvalidLevelException;
import com.seibel.distanthorizons.core.network.exceptions.InvalidSectionPosException;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.messages.base.CancelMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.generation.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.generation.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.generation.priority.GenTaskPriorityRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.generation.priority.GenTaskPriorityResponseMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.updates.FullDataChangeSummaryRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.updates.FullDataChangeSummaryResponseMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.updates.FullDataPartialUpdateMessage;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.coreapi.util.math.Vec3d;
import org.apache.logging.log4j.Logger;

import javax.annotation.CheckForNull;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

public class DhServerLevel extends DhLevel implements IDhServerLevel
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	public final ServerLevelModule serverside;
	private final IServerLevelWrapper serverLevelWrapper;
	
	private final RemotePlayerConnectionHandler remotePlayerConnectionHandler;
	private final ScopedNetworkEventSource<NetworkServer> eventSource;
	
	private final LinkedBlockingQueue<IServerPlayerWrapper> worldGenLoopingQueue = new LinkedBlockingQueue<>();
	private final ConcurrentMap<DhSectionPos, IncompleteDataSourceEntry> incompleteDataSources = new ConcurrentHashMap<>();
	private final ConcurrentMap<Long, IncompleteDataSourceEntry> fullDataRequests = new ConcurrentHashMap<>();
	private final AppliedConfigState<Integer> rateLimitConfig = new AppliedConfigState<>(Config.Client.Advanced.Multiplayer.serverNetworkingRateLimit);
	
	
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
		// TODO implement transparent message handling restriction by level
		// workaround:
// 		ServerPlayerState serverPlayerState = remotePlayerConnectionHandler.getConnectedPlayer(msg);
//		if (serverPlayerState == null) return;
//		
// 		if (serverPlayerState.serverPlayer.getLevel() != this.serverLevelWrapper)
// 			return;
		
		this.eventSource.registerHandler(FullDataSourceRequestMessage.class, msg ->
		{
			ServerPlayerState serverPlayerState = remotePlayerConnectionHandler.getConnectedPlayer(msg);
			if (serverPlayerState == null) return;
			
			if (serverPlayerState.serverPlayer.getLevel() != this.serverLevelWrapper)
				return;
			
			LOGGER.debug("FullDataSourceRequestMessage received at pos ({}, {}) with detail level {}", msg.dhSectionPos.getX(), msg.dhSectionPos.getZ(), msg.dhSectionPos.getDetailLevel());
			
			if (serverPlayerState.pendingFullDataRequests.incrementAndGet() > rateLimitConfig.get())
			{
				serverPlayerState.pendingFullDataRequests.decrementAndGet();
				msg.sendResponse(new RateLimitedException("Max concurrent requests: "+rateLimitConfig.get()));
				return;
			}
			
			while (true)
			{
				IncompleteDataSourceEntry entry = incompleteDataSources.computeIfAbsent(msg.dhSectionPos, pos -> {
					IncompleteDataSourceEntry newEntry = new IncompleteDataSourceEntry();
					serverside.dataFileHandler.readAsync(msg.dhSectionPos).thenAccept(fullDataSource -> {
						newEntry.fullDataSource = fullDataSource;
					});
					return newEntry;
				});
				// If this fails, current entry is being drained and need create another one
				if (entry.requestCollectionSemaphore.tryAcquire())
				{
					fullDataRequests.put(msg.futureId, entry);
					entry.requestMessages.put(msg.futureId, msg);
					entry.requestCollectionSemaphore.release();
					break;
				}
			}
		});
		
		this.eventSource.registerHandler(GenTaskPriorityRequestMessage.class, msg -> {
			ServerPlayerState serverPlayerState = remotePlayerConnectionHandler.getConnectedPlayer(msg);
			if (serverPlayerState == null) return;
			
			if (serverPlayerState.serverPlayer.getLevel() != this.serverLevelWrapper)
				return;
			
			msg.sendResponse(new GenTaskPriorityResponseMessage(
					this.serverside.dataFileHandler.getLoadStates(msg.posList)
			));
		});
		
		this.eventSource.registerHandler(FullDataChangeSummaryRequestMessage.class, msg ->
		{
			ServerPlayerState serverPlayerState = remotePlayerConnectionHandler.getConnectedPlayer(msg);
			if (serverPlayerState == null) return;
			
			if (serverPlayerState.serverPlayer.getLevel() != this.serverLevelWrapper)
				return;
			
			if (!msg.isLevelValid(this.serverLevelWrapper))
			{
				msg.sendResponse(new InvalidLevelException("Invalid level"));
				return;
			}
			
			// Load files and check checksums
			HashSet<DhSectionPos> changedPosList = new HashSet<>();
			for (Map.Entry<DhSectionPos, Integer> entry : msg.checksums.entrySet())
			{
				FullDataMetaFile metaFile = serverside.dataFileHandler.getFileIfExist(entry.getKey());
				if (metaFile == null)
				{
					msg.sendResponse(new InvalidSectionPosException("Not generated section pos: "+entry.getKey()));
					return;
				}
				
				if (entry.getValue() != metaFile.baseMetaData.checksum)
					changedPosList.add(entry.getKey());
			}
			
			msg.sendResponse(new FullDataChangeSummaryResponseMessage(changedPosList));
		});
		
		this.eventSource.registerHandler(CancelMessage.class, msg ->
		{
			IncompleteDataSourceEntry entry = this.fullDataRequests.remove(msg.futureId);
			if (entry == null) return;
			FullDataSourceRequestMessage requestMessage = entry.requestMessages.remove(msg.futureId);
			
			ServerPlayerState serverPlayerState = remotePlayerConnectionHandler.getConnectedPlayer(msg);
			if (serverPlayerState != null)
				serverPlayerState.pendingFullDataRequests.decrementAndGet();
			
			entry.requestCollectionSemaphore.acquireUninterruptibly(Short.MAX_VALUE);
			if (entry.requestMessages.isEmpty())
			{
				incompleteDataSources.remove(requestMessage.dhSectionPos);
				serverside.dataFileHandler.removeGenRequestIf(pos -> pos == requestMessage.dhSectionPos);
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
		boolean ignored = this.worldGenLoopingQueue.remove(serverPlayer);
	}
	
	public void serverTick()
	{
		chunkToLodBuilder.tick();
		
		for (Map.Entry<DhSectionPos, IncompleteDataSourceEntry> mapEntry : incompleteDataSources.entrySet())
		{
			IncompleteDataSourceEntry entry = mapEntry.getValue();
			
			if (entry.fullDataSource == null)
				continue;
			
			if (entry.fullDataSource instanceof IIncompleteFullDataSource)
			{
				IIncompleteFullDataSource incompleteSource = (IIncompleteFullDataSource) entry.fullDataSource;
				if (!incompleteSource.hasBeenPromoted())
					continue;
				entry.fullDataSource = incompleteSource.tryPromotingToCompleteDataSource();
			}
			
			LodUtil.assertTrue(entry.fullDataSource instanceof CompleteFullDataSource, "Invalid full data source");
			incompleteDataSources.remove(mapEntry.getKey());
			
			// This semaphore is intentionally acquired forever
			entry.requestCollectionSemaphore.acquireUninterruptibly(Short.MAX_VALUE);
			
			CompleteFullDataSource completeSource = (CompleteFullDataSource) entry.fullDataSource;
			for (FullDataSourceRequestMessage msg : entry.requestMessages.values())
			{
				this.fullDataRequests.remove(msg.futureId);
				
				ServerPlayerState serverPlayerState = remotePlayerConnectionHandler.getConnectedPlayer(msg);
				if (serverPlayerState == null)
					continue;
				
				serverPlayerState.pendingFullDataRequests.decrementAndGet();
				msg.sendResponse(new FullDataSourceResponseMessage(completeSource, this));
			}
		}
	}
	
	@Override
	public CompletableFuture<ChunkSizedFullDataAccessor> updateChunkAsync(IChunkWrapper chunk)
	{
		CompletableFuture<ChunkSizedFullDataAccessor> future = super.updateChunkAsync(chunk);
		if (future == null)
			return null;
		
		future.thenAccept(chunkSizedFullDataAccessor ->
		{
			for (IServerPlayerWrapper serverPlayer : worldGenLoopingQueue)
			{
				ServerPlayerState serverPlayerState = remotePlayerConnectionHandler.getPlayer(serverPlayer);
				if (serverPlayerState == null) continue;
				
				if (chunk.getChunkPos().distance(new DhChunkPos(serverPlayer.getPosition())) <= serverPlayerState.config.renderDistance)
					serverPlayerState.channelContext.writeAndFlush(new FullDataPartialUpdateMessage(chunkSizedFullDataAccessor, this));
			}
		});
		
		return future;
	}
	
	@Override
	public void saveWrites(ChunkSizedFullDataAccessor data)
	{
		DhSectionPos pos = data.getSectionPos();
		pos = pos.convertNewToDetailLevel(CompleteFullDataSource.SECTION_SIZE_OFFSET);
		this.getFileHandler().writeChunkDataToFile(pos, data);
	}
	
	@Override
	public int getMinY() { return getLevelWrapper().getMinHeight(); }
	
	@Override
	public void dumpRamUsage()
	{
		//TODO
	}
	
	@Override
	public void close()
	{
		super.close();
		serverside.close();
		LOGGER.info("Closed DHLevel for {}", getLevelWrapper());
	}
	
	@Override
	public CompletableFuture<Void> saveAsync() { return getFileHandler().flushAndSave(); }
	
	@Override
	public void doWorldGen()
	{
		boolean shouldDoWorldGen = true; //todo;
		boolean isWorldGenRunning = serverside.worldGenModule.isWorldGenRunning();
		if (shouldDoWorldGen && !isWorldGenRunning)
		{
			// start world gen
			serverside.worldGenModule.startWorldGen(serverside.dataFileHandler, new ServerLevelModule.WorldGenState(this));
		}
		else if (!shouldDoWorldGen && isWorldGenRunning)
		{
			// stop world gen
			serverside.worldGenModule.stopWorldGen(serverside.dataFileHandler);
		}
		
		if (serverside.worldGenModule.isWorldGenRunning())
		{
			IServerPlayerWrapper firstPlayer;
			synchronized (worldGenLoopingQueue)
			{
				firstPlayer = this.worldGenLoopingQueue.poll();
				if (firstPlayer == null)
					return;
				this.worldGenLoopingQueue.add(firstPlayer);
			}
			
			Vec3d position = firstPlayer.getPosition();
			serverside.worldGenModule.worldGenTick(new DhBlockPos2D((int) position.x, (int) position.z));
		}
	}
	
	@Override
	public IServerLevelWrapper getServerLevelWrapper() { return serverLevelWrapper; }
	
	@Override
	public ILevelWrapper getLevelWrapper() { return getServerLevelWrapper(); }
	
	@Override
	public IFullDataSourceProvider getFileHandler() { return serverside.dataFileHandler; }
	
	@Override
	public AbstractSaveStructure getSaveStructure()
	{
		return serverside.saveStructure;
	}
	
	@Override
	public void onWorldGenTaskComplete(DhSectionPos pos)
	{
		//TODO: Send packet to client
	}
	
	private static class IncompleteDataSourceEntry
	{
		@CheckForNull
		public IFullDataSource fullDataSource;
		public final ConcurrentMap<Long, FullDataSourceRequestMessage> requestMessages = new ConcurrentHashMap<>();
		public final Semaphore requestCollectionSemaphore = new Semaphore(Short.MAX_VALUE, true);
	}
}
