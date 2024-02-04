package com.seibel.distanthorizons.core.multiplayer.client;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
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
import java.text.MessageFormat;
import java.util.UUID;

public class ClientNetworkState implements Closeable
{
	protected static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	private final NetworkClient client;
	private final UUID playerUUID;
	public MultiplayerConfig config = new MultiplayerConfig();
	private final MultiplayerConfigChangeListener configChangeListener = new MultiplayerConfigChangeListener(this::onConfigChanged);
	
	private final F3Screen.NestedMessage f3Message = new F3Screen.NestedMessage(this::f3Log);
	
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
	
	private String[] f3Log()
	{
		if (!this.client.isClosed())
		{
			return new String[]{
					this.client.getRemoteAddress() != null
							? "Connected, ready: " + this.client.isReady()
							: MessageFormat.format("Disconnected, attempts left: {0} / {1}", this.client.getReconnectAttempts(), NetworkClient.FAILURE_RECONNECT_ATTEMPTS)
			};
		}
		else
		{
			return new String[]{
					this.client.getCloseReason() != null
							? "Disconnected: " + this.client.getCloseReason().getMessage()
							: "Disconnected (check logs for more information)"
			};
		}
	}
	
	@Override
	public void close()
	{
		this.f3Message.close();
		this.configChangeListener.close();
		this.client.close();
	}
}
