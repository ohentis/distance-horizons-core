package com.seibel.distanthorizons.core.multiplayer.server;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfig;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfigChangeListener;
import com.seibel.distanthorizons.core.network.netty.INettyConnection;
import com.seibel.distanthorizons.core.network.netty.NettyServer;
import com.seibel.distanthorizons.core.network.ScopedNetworkEventSource;
import com.seibel.distanthorizons.core.network.exceptions.InvalidLevelException;
import com.seibel.distanthorizons.core.network.messages.netty.base.AckMessage;
import com.seibel.distanthorizons.core.network.messages.netty.base.NettyCloseEvent;
import com.seibel.distanthorizons.core.network.messages.netty.ILevelRelatedMessage;
import com.seibel.distanthorizons.core.network.messages.netty.session.PlayerUUIDMessage;
import com.seibel.distanthorizons.core.network.messages.netty.session.RemotePlayerConfigMessage;
import com.seibel.distanthorizons.core.network.netty.TrackableNettyMessage;
import com.seibel.distanthorizons.core.network.netty.NettyMessage;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class RemotePlayerConnectionHandler implements Closeable
{
	private static final ConfigEntry<Boolean> GENERATE_MULTIPLE_DIMENSIONS_CONFIG = Config.Client.Advanced.Multiplayer.ServerNetworking.generateMultipleDimensions;
	
	private final ScopedNetworkEventSource<NettyServer, NettyMessage> eventSource;
	private final ConcurrentHashMap<UUID, ServerPlayerState> playersByUUID = new ConcurrentHashMap<>();
	private final BiMap<INettyConnection, ServerPlayerState> playersByConnection = Maps.synchronizedBiMap(HashBiMap.create());
	
	private final MultiplayerConfigChangeListener configChangeListener = new MultiplayerConfigChangeListener(this::onConfigChanged);
	
	public NettyServer server() { return this.eventSource.parent; }
	
	public RemotePlayerConnectionHandler(NettyServer nettyServer)
	{
		this.eventSource = new ScopedNetworkEventSource<>(nettyServer);
		this.registerNetworkHandlers();
	}
	
	private void registerNetworkHandlers()
	{
		this.eventSource.registerHandler(PlayerUUIDMessage.class, playerUUIDMessage ->
		{
			INettyConnection connection = playerUUIDMessage.getConnection();
			ServerPlayerState serverPlayerState = this.playersByUUID.get(playerUUIDMessage.playerUUID);
			
			if (serverPlayerState == null)
			{
				connection.disconnect("Player is not logged in.");
				return;
			}
			
			if (serverPlayerState.connection != null)
			{
				connection.disconnect("Another connection is already in use.");
				return;
			}
			
			serverPlayerState.connection = connection;
			this.playersByConnection.put(connection, serverPlayerState);
			
			playerUUIDMessage.sendResponse(new AckMessage());
		});
		
		this.eventSource.registerHandler(RemotePlayerConfigMessage.class, this.connectedPlayersOnly((remotePlayerConfigMessage, serverPlayerState) ->
		{
			serverPlayerState.config.clientConfig = (MultiplayerConfig) remotePlayerConfigMessage.payload;
			serverPlayerState.connection.sendMessage(new RemotePlayerConfigMessage(serverPlayerState.config));
		}));
		
		this.eventSource.registerHandler(NettyCloseEvent.class, closeEvent ->
		{
			ServerPlayerState dhPlayer = this.playersByConnection.remove(closeEvent.getConnection());
			if (dhPlayer != null)
			{
				dhPlayer.connection = null;
			}
		});
	}
	
	private void onConfigChanged()
	{
		for (ServerPlayerState serverPlayerState : this.getConnectedPlayers())
		{
			serverPlayerState.connection.sendMessage(new RemotePlayerConfigMessage(serverPlayerState.config));
		}
	}
	
	public <T extends NettyMessage> Consumer<T> connectedPlayersOnly(BiConsumer<T, ServerPlayerState> next)
	{
		return msg ->
		{
			ServerPlayerState serverPlayerState = this.getConnectedPlayer(msg);
			if (serverPlayerState != null)
			{
				next.accept(msg, serverPlayerState);
			}
		};
	}
	
	public <T extends NettyMessage> Consumer<T> currentLevelOnly(DhServerLevel level, BiConsumer<T, ServerPlayerState> next)
	{
		return this.connectedPlayersOnly((msg, serverPlayerState) ->
		{
			LodUtil.assertTrue(msg instanceof ILevelRelatedMessage, "Received message does not implement " + ILevelRelatedMessage.class.getSimpleName() + ": " + msg.getClass().getSimpleName());
			
			// Handle only in requested dimension
			if (!((ILevelRelatedMessage) msg).isSameLevelAs(level.getLevelWrapper()))
			{
				return;
			}
			
			// If player is not in this dimension and handling multiple dimensions at once is not allowed
			if (serverPlayerState.serverPlayer.getLevel() != level.getLevelWrapper()
					&& !GENERATE_MULTIPLE_DIMENSIONS_CONFIG.get())
			{
				// If the message can be replied to - reply with error, otherwise just ignore
				if (msg instanceof TrackableNettyMessage)
				{
					((TrackableNettyMessage) msg).sendResponse(new InvalidLevelException("Invalid level"));
				}
				
				return;
			}
			
			next.accept(msg, serverPlayerState);
		});
	}
	
	public Iterable<ServerPlayerState> getConnectedPlayers()
	{
		synchronized (this.playersByConnection)
		{
			return new ArrayList<>(this.playersByConnection.values());
		}
	}
	
	@Nullable
	public ServerPlayerState getConnectedPlayer(NettyMessage msg)
	{
		return this.playersByConnection.get(msg.getConnection());
	}
	
	public void registerJoinedPlayer(IServerPlayerWrapper serverPlayer)
	{
		this.playersByUUID.put(serverPlayer.getUUID(), new ServerPlayerState(serverPlayer));
	}
	
	public void unregisterLeftPlayer(IServerPlayerWrapper serverPlayer)
	{
		ServerPlayerState dhPlayer = this.playersByUUID.remove(serverPlayer.getUUID());
		INettyConnection connection = this.playersByConnection.inverse().remove(dhPlayer);
		if (connection != null)
		{
			connection.disconnect("You have logged out.");
		}
	}
	
	@Override
	public void close()
	{
		this.configChangeListener.close();
		this.eventSource.close();
		this.server().close();
	}
	
}
