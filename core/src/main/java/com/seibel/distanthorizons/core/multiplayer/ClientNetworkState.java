package com.seibel.distanthorizons.core.multiplayer;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.ScopedNetworkEventSource;
import com.seibel.distanthorizons.core.network.NetworkClient;
import com.seibel.distanthorizons.core.network.messages.AckMessage;
import com.seibel.distanthorizons.core.network.messages.HelloMessage;
import com.seibel.distanthorizons.core.network.messages.PlayerUUIDMessage;
import com.seibel.distanthorizons.core.network.messages.RemotePlayerConfigMessage;
import org.apache.logging.log4j.Logger;

import java.io.Closeable;
import java.util.UUID;

public class ClientNetworkState implements Closeable
{
	protected static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private final ScopedNetworkEventSource<NetworkClient> eventSource;
	private final UUID playerUUID;
	public MultiplayerConfig config = new MultiplayerConfig();
	
	public NetworkClient client() { return this.eventSource.parent; }
	
	public ClientNetworkState(NetworkClient networkClient, UUID playerUUID)
	{
		this.eventSource = new ScopedNetworkEventSource<>(networkClient);
		this.playerUUID = playerUUID;
		this.registerNetworkHandlers();
		this.client().startConnecting();
	}
	
	private void registerNetworkHandlers()
	{
		this.client().registerHandler(HelloMessage.class, helloMessage ->
		{
			LOGGER.info("Connected to server: "+helloMessage.getChannelContext().channel().remoteAddress());
			
			this.client().<AckMessage>sendRequest(new PlayerUUIDMessage(playerUUID))
					.thenCompose(ack -> this.client().<RemotePlayerConfigMessage>sendRequest(new RemotePlayerConfigMessage(new MultiplayerConfig()
					{{
						fullDataRequestRateLimit = Config.Client.Advanced.Multiplayer.serverNetworkingRateLimit.get();
					}})))
					.thenAccept(msg -> {
						this.config = msg.payload;
					})
					.exceptionally(throwable -> {
						LOGGER.error("Error while fetching server's config", throwable);
						return null;
					});
		});
	}
	
	public void close()
	{
		this.eventSource.close();
		this.client().close();
	}
}
