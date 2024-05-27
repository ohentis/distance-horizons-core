package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.LogManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RemotePlayerConnectionHandler
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	private final ConcurrentMap<IServerPlayerWrapper, ServerPlayerState> connectedPlayers = new ConcurrentHashMap<>();
	
	
	public void handlePluginMessage(IServerPlayerWrapper player, ByteBuf buffer)
	{
		this.connectedPlayers.get(player).session.decodeAndHandle(buffer);
	}
	
	public ServerPlayerState getConnectedPlayer(IServerPlayerWrapper player)
	{
		return this.connectedPlayers.get(player);
	}
	public Iterable<ServerPlayerState> getConnectedPlayers()
	{
		return this.connectedPlayers.values();
	}
	
	
	public ServerPlayerState registerJoinedPlayer(IServerPlayerWrapper serverPlayer)
	{
		ServerPlayerState state = new ServerPlayerState(serverPlayer);
		this.connectedPlayers.put(serverPlayer, state);
		return state;
	}
	
	public void unregisterLeftPlayer(IServerPlayerWrapper serverPlayer)
	{
		ServerPlayerState playerState = this.connectedPlayers.remove(serverPlayer);
		if (playerState != null)
		{
			playerState.close();
		}
	}
	
}