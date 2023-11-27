package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.network.IConnection;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;

import java.util.concurrent.atomic.AtomicInteger;

public class ServerPlayerState
{
    public IServerPlayerWrapper serverPlayer;
    public IConnection connection;
	
	public ServersideMultiplayerConfig config = new ServersideMultiplayerConfig();
    public final AtomicInteger pendingFullDataRequests = new AtomicInteger();
	
	
	
    public ServerPlayerState(IServerPlayerWrapper serverPlayer) { this.serverPlayer = serverPlayer; }
    
}

