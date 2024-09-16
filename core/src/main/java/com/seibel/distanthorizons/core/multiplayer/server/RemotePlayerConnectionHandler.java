package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import com.seibel.distanthorizons.core.network.session.NetworkSession;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

public class RemotePlayerConnectionHandler
{
	private final ConcurrentMap<IServerPlayerWrapper, ServerPlayerState> connectedPlayerStateByPlayerWrapper = new ConcurrentHashMap<>();
	private final ConcurrentMap<IServerPlayerWrapper, Queue<AbstractNetworkMessage>> messageQueueByPlayerWrapper = new ConcurrentHashMap<>();
	
	
	
	//========================//
	// player joining/leaving //
	//========================//
	
	public ServerPlayerState registerJoinedPlayer(IServerPlayerWrapper serverPlayer)
	{
		ServerPlayerState playerState = new ServerPlayerState(serverPlayer);
		this.connectedPlayerStateByPlayerWrapper.put(serverPlayer, playerState);
		
		Queue<AbstractNetworkMessage> queuedMessages = this.messageQueueByPlayerWrapper.get(serverPlayer);
		if (queuedMessages != null)
		{
			NetworkSession networkSession = playerState.networkSession;
			for (AbstractNetworkMessage message : queuedMessages)
			{
				networkSession.tryHandleMessage(message);
			}
			
			this.messageQueueByPlayerWrapper.remove(serverPlayer);
		}
		
		return playerState;
	}
	
	public void unregisterLeftPlayer(IServerPlayerWrapper serverPlayer)
	{
		ServerPlayerState playerState = this.connectedPlayerStateByPlayerWrapper.remove(serverPlayer);
		if (playerState != null)
		{
			playerState.close();
		}
	}
	
	
	
	//==========//
	// messages //
	//==========//
	
	public void handlePluginMessage(IServerPlayerWrapper player, AbstractNetworkMessage message)
	{
		ServerPlayerState playerState = this.connectedPlayerStateByPlayerWrapper.get(player);
		if (playerState != null)
		{
			playerState.networkSession.tryHandleMessage(message);
		}
		else
		{
			this.messageQueueByPlayerWrapper.computeIfAbsent(player, k -> new ConcurrentLinkedQueue<>()).add(message);
		}
	}
	
	
	
	//=========//
	// getters //
	//=========//
	
	@Nullable
	public ServerPlayerState getConnectedPlayer(IServerPlayerWrapper player) { return this.connectedPlayerStateByPlayerWrapper.get(player); }
	public Iterable<ServerPlayerState> getConnectedPlayers() { return this.connectedPlayerStateByPlayerWrapper.values(); }
	
	
	
	
}