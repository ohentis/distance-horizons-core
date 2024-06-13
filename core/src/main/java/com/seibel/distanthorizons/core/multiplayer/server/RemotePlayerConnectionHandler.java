package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.network.plugin.PluginChannelMessage;
import com.seibel.distanthorizons.core.network.plugin.PluginChannelSession;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RemotePlayerConnectionHandler
{
	private final ConcurrentMap<IServerPlayerWrapper, ServerPlayerState> connectedPlayers = new ConcurrentHashMap<>();
	
	
	public void handlePluginMessage(IServerPlayerWrapper player, PluginChannelMessage message)
	{
		PluginChannelSession session = this.connectedPlayers.get(player).session;
		message.setSession(session);
		session.tryHandleMessage(message);
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