package com.seibel.distanthorizons.core.world;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.seibel.distanthorizons.core.config.AppliedConfigState;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IIncompleteFullDataSource;
import com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataFileHandler;
import com.seibel.distanthorizons.core.file.structure.LocalSaveStructure;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.network.NetworkServer;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.messages.*;
import com.seibel.distanthorizons.core.multiplayer.RemotePlayer;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.coreapi.util.math.Vec3d;
import io.netty.channel.ChannelHandlerContext;

import javax.annotation.CheckForNull;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

public class DhServerWorld extends AbstractDhWorld implements IDhServerWorld
{
	private final HashMap<IServerLevelWrapper, DhServerLevel> levels;
	public final LocalSaveStructure saveStructure;

	private final NetworkServer networkServer;
	private final HashMap<UUID, RemotePlayer> playersByUUID;
	private final BiMap<ChannelHandlerContext, RemotePlayer> playersByConnection;
	
	private final ConcurrentLinkedQueue<IServerPlayerWrapper> worldGenLoopingQueue = new ConcurrentLinkedQueue<>();
	private final ConcurrentMap<DhSectionPos, IncompleteDataSourceEntry> incompleteDataSources = new ConcurrentHashMap<>();
	private final AppliedConfigState<Integer> rateLimitConfig = new AppliedConfigState<>(Config.Client.Advanced.Multiplayer.serverNetworkingRateLimit);


	public DhServerWorld()
	{
		super(EWorldEnvironment.Server_Only);

		this.saveStructure = new LocalSaveStructure();
		this.levels = new HashMap<>();

		// TODO move to global payload once server specific configs are implemented
		this.networkServer = new NetworkServer(25049);
		this.playersByUUID = new HashMap<>();
		this.playersByConnection = HashBiMap.create();
		this.registerNetworkHandlers();

		LOGGER.info("Started "+DhServerWorld.class.getSimpleName()+" of type "+this.environment);
	}

	private void registerNetworkHandlers()
	{
		this.networkServer.registerHandler(CloseMessage.class, closeMessage ->
		{
			RemotePlayer dhPlayer = this.playersByConnection.remove(closeMessage.getChannelContext());
			if (dhPlayer != null)
			{
				dhPlayer.channelContext = null;
			}
		});

		this.networkServer.registerHandler(PlayerUUIDMessage.class, playerUUIDMessage ->
		{
			ChannelHandlerContext channelContext = playerUUIDMessage.getChannelContext();
			RemotePlayer dhPlayer = this.playersByUUID.get(playerUUIDMessage.playerUUID);

			if (dhPlayer == null)
			{
				this.networkServer.disconnectClient(channelContext, "Player is not logged in.");
				return;
			}

			if (dhPlayer.channelContext != null)
			{
				this.networkServer.disconnectClient(channelContext, "Another connection is already in use.");
				return;
			}

			dhPlayer.channelContext = channelContext;
			this.playersByConnection.put(channelContext, dhPlayer);

			playerUUIDMessage.sendResponse(new AckMessage());
		});

		this.networkServer.registerHandler(RemotePlayerConfigMessage.class, remotePlayerConfigMessage ->
		{
			remotePlayerConfigMessage.payload.fullDataRequestRateLimit = Math.min(rateLimitConfig.get(), remotePlayerConfigMessage.payload.fullDataRequestRateLimit);
			remotePlayerConfigMessage.sendResponse(remotePlayerConfigMessage);
		});
		
		// This should be at DhServerLevel I guess
		this.networkServer.registerHandler(FullDataSourceRequestMessage.class, msg ->
		{
			LOGGER.info("FullDataSourceRequestMessage received at pos ({}, {}) with detail level {}", msg.dhSectionPos.sectionX, msg.dhSectionPos.sectionZ, msg.dhSectionPos.sectionDetailLevel);
			
			RemotePlayer remotePlayer = playersByConnection.get(msg.getChannelContext());
			if (remotePlayer.pendingFullDataRequests.incrementAndGet() > rateLimitConfig.get())
			{
				remotePlayer.pendingFullDataRequests.decrementAndGet();
				msg.sendResponse(new RateLimitedException("Max concurrent requests: "+rateLimitConfig.get()));
				return;
			}
			
			DhServerLevel level = this.getLevel(remotePlayer.serverPlayer.getLevel());
			GeneratedFullDataFileHandler handler = level.serverside.dataFileHandler;
			
			while (true)
			{
				IncompleteDataSourceEntry entry = incompleteDataSources.computeIfAbsent(msg.dhSectionPos, pos -> {
					IncompleteDataSourceEntry newEntry = new IncompleteDataSourceEntry();
					handler.read(msg.dhSectionPos).thenAccept(fullDataSource -> {
						newEntry.fullDataSource = fullDataSource;
					});
					return newEntry;
				});
				// If this fails, current entry is being drained and need create another one
				if (entry.requestCollectionSemaphore.tryAcquire())
				{
					entry.requestMessages.add(msg);
					entry.requestCollectionSemaphore.release();
					break;
				}
			}
		});
	}

	public void addPlayer(IServerPlayerWrapper serverPlayer)
	{
		this.playersByUUID.put(serverPlayer.getUUID(), new RemotePlayer(serverPlayer));
		this.worldGenLoopingQueue.add(serverPlayer);
	}
	public void removePlayer(IServerPlayerWrapper serverPlayer)
	{
		this.worldGenLoopingQueue.remove(serverPlayer);
		RemotePlayer dhPlayer = this.playersByUUID.remove(serverPlayer.getUUID());
		ChannelHandlerContext channelContext = this.playersByConnection.inverse().remove(dhPlayer);
		if (channelContext != null)
		{
			this.networkServer.disconnectClient(channelContext, "You are being disconnected.");
		}
	}

	@Override
	public DhServerLevel getOrLoadLevel(ILevelWrapper wrapper)
	{
		if (!(wrapper instanceof IServerLevelWrapper))
		{
			return null;
		}

		return this.levels.computeIfAbsent((IServerLevelWrapper) wrapper, (serverLevelWrapper) ->
		{
			File levelFile = this.saveStructure.getLevelFolder(wrapper);
			LodUtil.assertTrue(levelFile != null);
			return new DhServerLevel(this.saveStructure, serverLevelWrapper);
		});
	}

	@Override
	public DhServerLevel getLevel(ILevelWrapper wrapper)
	{
		if (!(wrapper instanceof IServerLevelWrapper))
		{
			return null;
		}

		return this.levels.get(wrapper);
	}

	@Override
	public Iterable<? extends IDhLevel> getAllLoadedLevels() { return this.levels.values(); }

	@Override
	public void unloadLevel(ILevelWrapper wrapper)
	{
		if (!(wrapper instanceof IServerLevelWrapper))
		{
			return;
		}

		if (this.levels.containsKey(wrapper))
		{
			LOGGER.info("Unloading level {} ", this.levels.get(wrapper));
			this.levels.remove(wrapper).close();
		}
	}

	public void serverTick() {
		this.levels.values().forEach(DhServerLevel::serverTick);
		
		if (rateLimitConfig.pollNewValue())
		{
			for (RemotePlayer remotePlayer : playersByConnection.values())
			{
				remotePlayer.payload.fullDataRequestRateLimit = rateLimitConfig.get();
				remotePlayer.channelContext.writeAndFlush(new RemotePlayerConfigMessage(remotePlayer.payload));
			}
		}
		
		for (Iterator<IncompleteDataSourceEntry> it = incompleteDataSources.values().iterator(); it.hasNext(); )
		{
			IncompleteDataSourceEntry entry = it.next();
			if (entry.fullDataSource == null) continue;
			
			if (entry.fullDataSource instanceof IIncompleteFullDataSource)
			{
				IIncompleteFullDataSource incompleteSource = (IIncompleteFullDataSource) entry.fullDataSource;
				if (!incompleteSource.hasBeenPromoted()) continue;
				entry.fullDataSource = incompleteSource.tryPromotingToCompleteDataSource();
			}
			
			LodUtil.assertTrue(entry.fullDataSource instanceof CompleteFullDataSource, "Invalid full data source");
			
			it.remove();
			// This semaphore is intentionally acquired forever
			entry.requestCollectionSemaphore.acquireUninterruptibly(Short.MAX_VALUE);
			
			CompleteFullDataSource completeSource = (CompleteFullDataSource) entry.fullDataSource;
			for (FullDataSourceRequestMessage msg : entry.requestMessages)
			{
				RemotePlayer remotePlayer = playersByConnection.get(msg.getChannelContext());
				if (remotePlayer == null) continue;
				remotePlayer.pendingFullDataRequests.decrementAndGet();
				
				DhServerLevel level = this.getLevel(remotePlayer.serverPlayer.getLevel());
				msg.sendResponse(new FullDataSourceResponseMessage(completeSource, level));
			}
		}
	}

	public void doWorldGen() {
		this.levels.values().forEach(level -> {
			// TODO Deal with dimensions and dimension switches
			
			IServerPlayerWrapper firstPlayer = this.worldGenLoopingQueue.poll();
			if (firstPlayer == null) {
				level.doWorldGen();
				return;
			}
			this.worldGenLoopingQueue.add(firstPlayer);
			
			Vec3d position = firstPlayer.getPosition();
			level.doWorldGen(new DhBlockPos2D((int) position.x, (int) position.z));
		});
	}

	@Override
	public CompletableFuture<Void> saveAndFlush()
	{
		return CompletableFuture.allOf(this.levels.values().stream().map(DhServerLevel::saveAsync).toArray(CompletableFuture[]::new));
	}

	@Override
	public void close()
	{
		this.networkServer.close();

		for (DhServerLevel level : this.levels.values())
		{
			LOGGER.info("Unloading level " + level.getLevelWrapper().getDimensionType().getDimensionName());
			level.close();
		}

		this.levels.clear();
		LOGGER.info("Closed DhWorld of type "+this.environment);
	}

	private static class IncompleteDataSourceEntry
	{
		@CheckForNull
		public IFullDataSource fullDataSource;
		public final Set<FullDataSourceRequestMessage> requestMessages = ConcurrentHashMap.newKeySet();
		public final Semaphore requestCollectionSemaphore = new Semaphore(Short.MAX_VALUE, true);
	}
	
}
