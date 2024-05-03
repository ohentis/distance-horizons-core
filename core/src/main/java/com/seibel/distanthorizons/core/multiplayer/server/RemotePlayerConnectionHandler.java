package com.seibel.distanthorizons.core.multiplayer.server;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfig;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfigChangeListener;
import com.seibel.distanthorizons.core.network.exceptions.InvalidLevelException;
import com.seibel.distanthorizons.core.network.messages.netty.ILevelRelatedMessage;
import com.seibel.distanthorizons.core.network.messages.netty.base.AckMessage;
import com.seibel.distanthorizons.core.network.messages.netty.base.NettyCloseEvent;
import com.seibel.distanthorizons.core.network.messages.netty.session.PlayerUUIDMessage;
import com.seibel.distanthorizons.core.network.messages.netty.session.RemotePlayerConfigMessage;
import com.seibel.distanthorizons.core.network.netty.INettyConnection;
import com.seibel.distanthorizons.core.network.netty.NettyMessage;
import com.seibel.distanthorizons.core.network.netty.NettyServer;
import com.seibel.distanthorizons.core.network.netty.TrackableNettyMessage;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.seibel.distanthorizons.core.config.Config.Client.Advanced.Multiplayer.ServerNetworking;

public class RemotePlayerConnectionHandler implements Closeable
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	private static final ConfigEntry<Boolean> GENERATE_MULTIPLE_DIMENSIONS_CONFIG = Config.Client.Advanced.Multiplayer.ServerNetworking.generateMultipleDimensions;
	
	private final NettyServer server = new NettyServer(ServerNetworking.serverPort.get());
	private final ConcurrentHashMap<UUID, ServerPlayerState> playersByUUID = new ConcurrentHashMap<>();
	private final BiMap<INettyConnection, ServerPlayerState> playersByConnection = Maps.synchronizedBiMap(HashBiMap.create());
	private final MultiplayerConfigChangeListener configChangeListener = new MultiplayerConfigChangeListener(this::onConfigChanged);
	
	private final ConfigChangeListener<Integer> portChangeListener = new ConfigChangeListener<>(ServerNetworking.serverPort, this::onServerPortChanged);
	private final ConfigChangeListener<String> connectIpOverrideChangeListener = new ConfigChangeListener<>(ServerNetworking.connectIpOverride, this::onConnectOverridesChanged);
	private final ConfigChangeListener<Integer> connectPortOverrideChangeListener = new ConfigChangeListener<>(ServerNetworking.connectPortOverride, this::onConnectOverridesChanged);
	
	
	public NettyServer server() { return this.server; }
	
	public RemotePlayerConnectionHandler()
	{
		this.server.registerHandler(PlayerUUIDMessage.class, playerUUIDMessage ->
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
		
		this.server.registerHandler(RemotePlayerConfigMessage.class, this.connectedPlayersOnly((remotePlayerConfigMessage, serverPlayerState) ->
		{
			serverPlayerState.config.clientConfig = (MultiplayerConfig) remotePlayerConfigMessage.payload;
			serverPlayerState.connection.sendMessage(new RemotePlayerConfigMessage(serverPlayerState.config));
		}));
		
		this.server.registerHandler(NettyCloseEvent.class, closeEvent ->
		{
			ServerPlayerState dhPlayer = this.playersByConnection.remove(closeEvent.getConnection());
			if (dhPlayer != null)
			{
				dhPlayer.clearRateLimiterSets();
				dhPlayer.connection = null;
			}
		});
	}
	
	public void handlePluginMessage(IServerPlayerWrapper player, ByteBuf buffer)
	{
		this.playersByUUID.get(player.getUUID()).pluginChannelHandler.decodeAndHandle(buffer, player);
	}
	
	private void onConfigChanged()
	{
		for (ServerPlayerState serverPlayerState : this.getConnectedPlayers())
		{
			serverPlayerState.connection.sendMessage(new RemotePlayerConfigMessage(serverPlayerState.config));
		}
	}
	
	private void onServerPortChanged(int ignored)
	{
		LOGGER.warn("Server port change requires a server restart to take effect.");
	}
	private void onConnectOverridesChanged(@Nullable Object ignored)
	{
		for (ServerPlayerState playerState : this.playersByUUID.values())
		{
			playerState.sendConnectInfo();
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
					((TrackableNettyMessage) msg).sendResponse(new InvalidLevelException(MessageFormat.format(
							"Generation not allowed. Requested dimension: {0}, player dimension: {1}",
							level.getLevelWrapper().getDimensionType().getDimensionName(),
							serverPlayerState.serverPlayer.getLevel().getDimensionType().getDimensionName()
					)));
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
		this.playersByUUID.put(serverPlayer.getUUID(), new ServerPlayerState(serverPlayer, this.server.port));
	}
	
	public void unregisterLeftPlayer(IServerPlayerWrapper serverPlayer)
	{
		ServerPlayerState playerState = this.playersByUUID.remove(serverPlayer.getUUID());
		playerState.close();
		
		INettyConnection connection = this.playersByConnection.inverse().remove(playerState);
		if (connection != null)
		{
			connection.disconnect("You have logged out.");
		}
	}
	
	@Override
	public void close()
	{
		this.portChangeListener.close();
		this.connectIpOverrideChangeListener.close();
		this.connectPortOverrideChangeListener.close();
		
		this.configChangeListener.close();
		this.server().close();
	}
	
}