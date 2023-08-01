package com.seibel.distanthorizons.core.multiplayer;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.seibel.distanthorizons.core.network.ChildNetworkEventSource;
import com.seibel.distanthorizons.core.network.NetworkServer;
import com.seibel.distanthorizons.core.network.messages.AckMessage;
import com.seibel.distanthorizons.core.network.messages.CloseMessage;
import com.seibel.distanthorizons.core.network.messages.PlayerUUIDMessage;
import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import io.netty.channel.ChannelHandlerContext;

import java.util.HashMap;
import java.util.UUID;

public class RemotePlayerConnectionHandler
{
	public final ChildNetworkEventSource<NetworkServer> eventSource;
	private final HashMap<UUID, RemotePlayer> playersByUUID = new HashMap<>();
	private final BiMap<ChannelHandlerContext, RemotePlayer> playersByConnection = HashBiMap.create();
	
	public RemotePlayerConnectionHandler(NetworkServer networkServer)
	{
		this.eventSource = new ChildNetworkEventSource<>(networkServer);
		this.registerNetworkHandlers();
	}
	
	private void registerNetworkHandlers()
	{
		this.eventSource.registerHandler(PlayerUUIDMessage.class, playerUUIDMessage ->
		{
			ChannelHandlerContext channelContext = playerUUIDMessage.getChannelContext();
			RemotePlayer dhPlayer = this.playersByUUID.get(playerUUIDMessage.playerUUID);
			
			if (dhPlayer == null)
			{
				this.eventSource.parent.disconnectClient(channelContext, "Player is not logged in.");
				return;
			}
			
			if (dhPlayer.channelContext != null)
			{
				this.eventSource.parent.disconnectClient(channelContext, "Another connection is already in use.");
				return;
			}
			
			dhPlayer.channelContext = channelContext;
			this.playersByConnection.put(channelContext, dhPlayer);
			
			playerUUIDMessage.sendResponse(new AckMessage());
		});
		
		this.eventSource.registerHandler(CloseMessage.class, closeMessage ->
		{
			RemotePlayer dhPlayer = this.playersByConnection.remove(closeMessage.getChannelContext());
			if (dhPlayer != null)
			{
				dhPlayer.channelContext = null;
			}
		});
	}
	
	public Iterable<RemotePlayer> getConnectedPlayers()
	{
		return playersByConnection.values();
	}
	
	public RemotePlayer getConnectedPlayer(NetworkMessage msg)
	{
		return playersByConnection.get(msg.getChannelContext());
	}
	
	public RemotePlayer getPlayer(IServerPlayerWrapper serverPlayer)
	{
		return playersByUUID.get(serverPlayer.getUUID());
	}
	
	public void mcPlayerJoined(IServerPlayerWrapper serverPlayer)
	{
		this.playersByUUID.put(serverPlayer.getUUID(), new RemotePlayer(serverPlayer));
	}
	
	public void mcPlayerLeft(IServerPlayerWrapper serverPlayer)
	{
		RemotePlayer dhPlayer = this.playersByUUID.remove(serverPlayer.getUUID());
		ChannelHandlerContext channelContext = this.playersByConnection.inverse().remove(dhPlayer);
		if (channelContext != null)
		{
			this.eventSource.parent.disconnectClient(channelContext, "You are being disconnected.");
		}
	}
	
}
