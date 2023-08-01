package com.seibel.distanthorizons.core.multiplayer;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.network.ChildNetworkEventSource;
import com.seibel.distanthorizons.core.network.NetworkClient;
import com.seibel.distanthorizons.core.network.messages.RemotePlayerConfigMessage;

import java.io.Closeable;

public class ClientNetworkState implements Closeable
{
	public final ChildNetworkEventSource<NetworkClient> eventSource;
	public RemotePlayer.Payload config = new RemotePlayer.Payload();
	
	public ClientNetworkState(NetworkClient networkClient)
	{
		this.eventSource = new ChildNetworkEventSource<>(networkClient);
		this.registerNetworkHandlers();
	}
	
	private void registerNetworkHandlers()
	{
		eventSource.registerHandler(RemotePlayerConfigMessage.class, msg -> {
			this.config = msg.payload;
		});
	}
	
	public void close()
	{
		this.eventSource.close();
	}
}
