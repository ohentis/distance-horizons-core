package com.seibel.distanthorizons.core.world;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IIncompleteFullDataSource;
import com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataFileHandler;
import com.seibel.distanthorizons.core.file.structure.LocalSaveStructure;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.network.NetworkServer;
import com.seibel.distanthorizons.core.network.messages.*;
import com.seibel.distanthorizons.core.network.objects.RemotePlayer;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
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
	private ConcurrentMap<DhSectionPos, IncompleteDataSourceEntry> incompleteDataSources = new ConcurrentHashMap<>();
	


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
			// TODO Take notice of and/or constrain received payload
			remotePlayerConfigMessage.sendResponse(remotePlayerConfigMessage);
		});
		
		// This should be at DhServerLevel I guess
		this.networkServer.registerHandler(FullDataSourceRequestMessage.class, msg ->
		{
			LOGGER.info("FullDataSourceRequestMessage received at pos ({}, {}) with detail level {}", msg.dhSectionPos.sectionX, msg.dhSectionPos.sectionZ, msg.dhSectionPos.sectionDetailLevel);
			
			DhServerLevel level = this.getLevel(playersByConnection.get(msg.getChannelContext()).serverPlayer.getLevel());
			GeneratedFullDataFileHandler handler = level.serverside.dataFileHandler;
			
			incompleteDataSources.computeIfAbsent(msg.dhSectionPos, pos -> {
				IncompleteDataSourceEntry entry = new IncompleteDataSourceEntry();
				handler.read(msg.dhSectionPos).thenAccept(fullDataSource -> {
					entry.fullDataSource = fullDataSource;
				});
				return entry;
			}).requestMessages.add(msg);
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
		
		for (Iterator<IncompleteDataSourceEntry> it = incompleteDataSources.values().iterator(); it.hasNext(); )
		{
			IncompleteDataSourceEntry entry = it.next();
			if (entry.fullDataSource instanceof IIncompleteFullDataSource)
			{
				IIncompleteFullDataSource incompleteSource = (IIncompleteFullDataSource) entry.fullDataSource;
				if (!incompleteSource.hasBeenPromoted()) continue;
				entry.fullDataSource = incompleteSource.tryPromotingToCompleteDataSource();
			}
			
			if (!(entry.fullDataSource instanceof CompleteFullDataSource))
				LodUtil.assertNotReach("Invalid full data source");
				
			it.remove();
			CompleteFullDataSource completeSource = (CompleteFullDataSource) entry.fullDataSource;
			
			for (FullDataSourceRequestMessage msg : entry.requestMessages)
			{
				RemotePlayer remotePlayer = playersByConnection.get(msg.getChannelContext());
				if (remotePlayer == null) continue;
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
			
			com.seibel.distanthorizons.coreapi.util.math.Vec3d position = firstPlayer.getPosition();
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
		public Set<FullDataSourceRequestMessage> requestMessages = ConcurrentHashMap.newKeySet();
	}
	
}
