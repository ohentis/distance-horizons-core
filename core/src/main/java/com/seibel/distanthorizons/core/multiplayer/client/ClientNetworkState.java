package com.seibel.distanthorizons.core.multiplayer.client;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfig;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfigChangeListener;
import com.seibel.distanthorizons.core.network.NetworkClient;
import com.seibel.distanthorizons.core.network.ScopedNetworkEventSource;
import com.seibel.distanthorizons.core.network.messages.base.AckMessage;
import com.seibel.distanthorizons.core.network.messages.base.HelloMessage;
import com.seibel.distanthorizons.core.network.messages.session.PlayerUUIDMessage;
import com.seibel.distanthorizons.core.network.messages.session.RemotePlayerConfigMessage;
import org.apache.logging.log4j.LogManager;

import java.io.Closeable;
import java.util.UUID;

public class ClientNetworkState implements Closeable
{
	protected static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	private final NetworkClient client;
	private final UUID playerUUID;
	public MultiplayerConfig config = new MultiplayerConfig();
	private final MultiplayerConfigChangeListener configChangeListener = new MultiplayerConfigChangeListener(this::onConfigChanged);
	
	/**
	 * Returns the client used by this instance. <p>
	 * If you need to subscribe to any packet events, create an instance of {@link ScopedNetworkEventSource} using the returned instance.
	 */
	public NetworkClient getClient() { return this.client; }
	
	/**
	 * Constructs a new instance.
	 *
	 * @param networkClient Client to use. It is assumed that this client will be at full control by this instance.
	 * @param playerUUID UUID of a player connected.
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
			LOGGER.info("Connected to server: "+helloMessage.getConnection().getRemoteAddress());
			
			this.getClient().sendRequest(new PlayerUUIDMessage(this.playerUUID), AckMessage.class)
					.thenAccept(ack -> this.getClient().sendMessage(new RemotePlayerConfigMessage(new MultiplayerConfig())))
					.exceptionally(throwable -> {
						LOGGER.error("Error while fetching server's config", throwable);
						return null;
					});
		});
		
		this.client.registerHandler(RemotePlayerConfigMessage.class, msg ->
		{
			LOGGER.info("Connection config was changed: " + msg.payload);
			this.config = (MultiplayerConfig) msg.payload;
		});
	}
	
	private void onConfigChanged()
	{
		this.getClient().sendMessage(new RemotePlayerConfigMessage(new MultiplayerConfig()));
	}
	
	@Override
	public void close()
	{
		this.configChangeListener.close();
		this.client.close();
	}
}
