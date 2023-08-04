package com.seibel.distanthorizons.core.multiplayer;

import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import io.netty.channel.ChannelHandlerContext;

import java.util.concurrent.atomic.AtomicInteger;

public class ServerPlayerState
{
    public IServerPlayerWrapper serverPlayer;
    public MultiplayerConfig config;
    public ChannelHandlerContext channelContext;
    public final AtomicInteger pendingFullDataRequests = new AtomicInteger();
	
	
	
    public ServerPlayerState(IServerPlayerWrapper serverPlayer) { this.serverPlayer = serverPlayer; }
    
}

