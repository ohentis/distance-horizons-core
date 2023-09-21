package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import io.netty.channel.ChannelHandlerContext;

import java.util.concurrent.atomic.AtomicInteger;

public class ServerPlayerState
{
    public IServerPlayerWrapper serverPlayer;
    public ChannelHandlerContext channelContext;
	
	public ServersideMultiplayerConfig config = new ServersideMultiplayerConfig();
    public final AtomicInteger pendingFullDataRequests = new AtomicInteger();
	
	
	
    public ServerPlayerState(IServerPlayerWrapper serverPlayer) { this.serverPlayer = serverPlayer; }
    
}

