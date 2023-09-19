package com.seibel.distanthorizons.core.multiplayer;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.ScopedNetworkEventSource;
import com.seibel.distanthorizons.core.network.NetworkClient;
import com.seibel.distanthorizons.core.network.messages.base.AckMessage;
import com.seibel.distanthorizons.core.network.messages.base.HelloMessage;
import com.seibel.distanthorizons.core.network.messages.session.PlayerUUIDMessage;
import com.seibel.distanthorizons.core.network.messages.session.RemotePlayerConfigMessage;
import org.apache.logging.log4j.Logger;

import java.io.Closeable;
import java.util.UUID;

public class ClientNetworkState implements Closeable
{
	protected static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private final NetworkClient client;
	private final UUID playerUUID;
	public MultiplayerConfig config = new MultiplayerConfig();
	
	/**
	 * Returns the client used by this instance. <p>
	 * If you need to subscribe to any packet events, create an instance of {@link ScopedNetworkEventSource} using the returned instance.
	 */
	public NetworkClient getClient() { return this.client; }
	
	/**
	 * Constructs a new instance.
	 *
	 * @param networkClient Client to use. It is assumed that this client will be at full control by this instance.
	 * @param playerUUID UUID of a player connected
	 */
	public ClientNetworkState(NetworkClient networkClient, UUID playerUUID)
	{
		this.client = networkClient;
		this.playerUUID = playerUUID;
		this.registerNetworkHandlers();
		this.client.startConnecting();
	}
	
	private void registerNetworkHandlers()
	{
		this.client.registerHandler(HelloMessage.class, helloMessage ->
		{
			LOGGER.info("Connected to server: "+helloMessage.getChannelContext().channel().remoteAddress());
			
			this.getClient().sendRequest(new PlayerUUIDMessage(playerUUID), AckMessage.class)
					.thenCompose(ack -> this.getClient().sendRequest(new RemotePlayerConfigMessage(new MultiplayerConfig()
					{{
						renderDistance = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistance.get();
						fullDataRequestRateLimit = Config.Client.Advanced.Multiplayer.serverNetworkingRateLimit.get();
					}}), RemotePlayerConfigMessage.class))
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
		this.client.close();
	}
}
