package com.seibel.distanthorizons.core.multiplayer.server;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfig;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfigChangeListener;
import com.seibel.distanthorizons.core.network.IConnection;
import com.seibel.distanthorizons.core.network.NetworkServer;
import com.seibel.distanthorizons.core.network.ScopedNetworkEventSource;
import com.seibel.distanthorizons.core.network.messages.base.AckMessage;
import com.seibel.distanthorizons.core.network.messages.base.CloseEvent;
import com.seibel.distanthorizons.core.network.messages.base.ILevelRelatedMessage;
import com.seibel.distanthorizons.core.network.messages.session.PlayerUUIDMessage;
import com.seibel.distanthorizons.core.network.messages.session.RemotePlayerConfigMessage;
import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class RemotePlayerConnectionHandler implements Closeable
{
	private final ScopedNetworkEventSource<NetworkServer> eventSource;
	private final HashMap<UUID, ServerPlayerState> playersByUUID = new HashMap<>();
	private final BiMap<IConnection, ServerPlayerState> playersByConnection = HashBiMap.create();
	
	private final MultiplayerConfigChangeListener configChangeListener = new MultiplayerConfigChangeListener(this::onConfigChanged); 
	
	public NetworkServer server() { return this.eventSource.parent; }
	
	public RemotePlayerConnectionHandler(NetworkServer networkServer)
	{
		this.eventSource = new ScopedNetworkEventSource<>(networkServer);
		this.registerNetworkHandlers();
	}
	
	private void registerNetworkHandlers()
	{
		this.eventSource.registerHandler(PlayerUUIDMessage.class, playerUUIDMessage ->
		{
			IConnection connection = playerUUIDMessage.getConnection();
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
		
		this.eventSource.registerHandler(CloseEvent.class, closeEvent ->
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
			serverPlayerState.connection.sendMessage(new RemotePlayerConfigMessage(serverPlayerState.config));
	}
	
	public <T extends NetworkMessage> Consumer<T> connectedPlayersOnly(BiConsumer<T, ServerPlayerState> next)
	{
		return msg ->
		{
			ServerPlayerState serverPlayerState = getConnectedPlayer(msg);
			if (serverPlayerState != null)
				next.accept(msg, serverPlayerState);
		};
	}
	
	public <T extends NetworkMessage> Consumer<T> currentLevelOnly(DhServerLevel level, BiConsumer<T, ServerPlayerState> next)
	{
		return connectedPlayersOnly((msg, serverPlayerState) ->
		{
			if (serverPlayerState.serverPlayer.getLevel() != level.getLevelWrapper())
				return;
			
			if (msg instanceof ILevelRelatedMessage && ((ILevelRelatedMessage) msg).sendExceptionIfLevelInvalid(level.getLevelWrapper()))
				return;
			
			next.accept(msg, serverPlayerState);
		});
	}
	
	public Iterable<ServerPlayerState> getConnectedPlayers()
	{
		return playersByConnection.values();
	}
	
	@Nullable
	public ServerPlayerState getConnectedPlayer(NetworkMessage msg)
	{
		return playersByConnection.get(msg.getConnection());
	}
	
	public void registerJoinedPlayer(IServerPlayerWrapper serverPlayer)
	{
		this.playersByUUID.put(serverPlayer.getUUID(), new ServerPlayerState(serverPlayer));
	}
	
	public void unregisterLeftPlayer(IServerPlayerWrapper serverPlayer)
	{
		ServerPlayerState dhPlayer = this.playersByUUID.remove(serverPlayer.getUUID());
		IConnection connection = this.playersByConnection.inverse().remove(dhPlayer);
		if (connection != null)
			connection.disconnect("You are being disconnected.");
	}
	
	@Override
	public void close()
	{
		this.configChangeListener.close();
		this.eventSource.close();
		this.server().close();
	}
	
}
