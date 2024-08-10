package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.network.messages.NetworkMessage;
import com.seibel.distanthorizons.core.network.session.Session;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

public class RemotePlayerConnectionHandler
{
	private final ConcurrentMap<IServerPlayerWrapper, ServerPlayerState> connectedPlayers = new ConcurrentHashMap<>();
	private final ConcurrentMap<IServerPlayerWrapper, Queue<NetworkMessage>> messageQueue = new ConcurrentHashMap<>();
	
	
	public void handlePluginMessage(IServerPlayerWrapper player, NetworkMessage message)
	{
		ServerPlayerState playerState = this.connectedPlayers.get(player);
		if (playerState != null)
		{
			playerState.session.tryHandleMessage(message);
		}
		else
		{
			this.messageQueue.computeIfAbsent(player, k -> new ConcurrentLinkedQueue<>()).add(message);
		}
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
		
		Queue<NetworkMessage> queuedMessages = this.messageQueue.get(serverPlayer);
		if (queuedMessages != null)
		{
			Session session = state.session;
			for (NetworkMessage message : queuedMessages)
			{
				session.tryHandleMessage(message);
			}
			
			this.messageQueue.remove(serverPlayer);
		}
		
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