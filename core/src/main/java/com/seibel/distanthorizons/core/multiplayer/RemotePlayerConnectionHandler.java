package com.seibel.distanthorizons.core.multiplayer;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.seibel.distanthorizons.core.network.ScopedNetworkEventSource;
import com.seibel.distanthorizons.core.network.NetworkServer;
import com.seibel.distanthorizons.core.network.messages.base.AckMessage;
import com.seibel.distanthorizons.core.network.messages.base.CloseEvent;
import com.seibel.distanthorizons.core.network.messages.session.PlayerUUIDMessage;
import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import io.netty.channel.ChannelHandlerContext;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.util.HashMap;
import java.util.UUID;

public class RemotePlayerConnectionHandler implements Closeable
{
	private final ScopedNetworkEventSource<NetworkServer> eventSource;
	private final HashMap<UUID, ServerPlayerState> playersByUUID = new HashMap<>();
	private final BiMap<ChannelHandlerContext, ServerPlayerState> playersByConnection = HashBiMap.create();
	
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
			ChannelHandlerContext channelContext = playerUUIDMessage.getChannelContext();
			ServerPlayerState dhPlayer = this.playersByUUID.get(playerUUIDMessage.playerUUID);
			
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
		
		this.eventSource.registerHandler(CloseEvent.class, closeEvent ->
		{
			ServerPlayerState dhPlayer = this.playersByConnection.remove(closeEvent.getChannelContext());
			if (dhPlayer != null)
			{
				dhPlayer.channelContext = null;
			}
		});
	}
	
	public Iterable<ServerPlayerState> getConnectedPlayers()
	{
		return playersByConnection.values();
	}
	
	@Nullable
	public ServerPlayerState getConnectedPlayer(NetworkMessage msg)
	{
		return playersByConnection.get(msg.getChannelContext());
	}
	
	@Nullable
	public ServerPlayerState getPlayer(IServerPlayerWrapper serverPlayer)
	{
		return playersByUUID.get(serverPlayer.getUUID());
	}
	
	public void registerJoinedPlayer(IServerPlayerWrapper serverPlayer)
	{
		this.playersByUUID.put(serverPlayer.getUUID(), new ServerPlayerState(serverPlayer));
	}
	
	public void unregisterLeftPlayer(IServerPlayerWrapper serverPlayer)
	{
		ServerPlayerState dhPlayer = this.playersByUUID.remove(serverPlayer.getUUID());
		ChannelHandlerContext channelContext = this.playersByConnection.inverse().remove(dhPlayer);
		if (channelContext != null)
		{
			this.eventSource.parent.disconnectClient(channelContext, "You are being disconnected.");
		}
	}
	
	@Override
	public void close()
	{
		this.eventSource.close();
		this.server().close();
	}
	
}
