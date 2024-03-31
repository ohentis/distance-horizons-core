package com.seibel.distanthorizons.core.multiplayer.client;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfig;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfigChangeListener;
import com.seibel.distanthorizons.core.network.netty.NettyClient;
import com.seibel.distanthorizons.core.network.ScopedNetworkEventSource;
import com.seibel.distanthorizons.core.network.messages.netty.base.AckMessage;
import com.seibel.distanthorizons.core.network.messages.netty.base.NettyCloseEvent;
import com.seibel.distanthorizons.core.network.messages.netty.base.HelloMessage;
import com.seibel.distanthorizons.core.network.messages.netty.session.PlayerUUIDMessage;
import com.seibel.distanthorizons.core.network.messages.netty.session.RemotePlayerConfigMessage;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import org.apache.logging.log4j.LogManager;

import java.io.Closeable;
import java.text.MessageFormat;
import java.util.UUID;

public class ClientNetworkState implements Closeable
{
	protected static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	
	private final NettyClient client = new NettyClient();
	private final UUID playerUUID = MC_CLIENT.getPlayerUUID();
	
	
	public MultiplayerConfig config = new MultiplayerConfig();
	private volatile boolean configReceived = false;
	private final MultiplayerConfigChangeListener configChangeListener = new MultiplayerConfigChangeListener(this::onConfigChanged);
	public boolean isReady() { return this.configReceived; }
	
	private final F3Screen.NestedMessage f3Message = new F3Screen.NestedMessage(this::f3Log);
	
	/**
	 * Returns the client used by this instance. <p>
	 * If you need to subscribe to any packet events, create an instance of {@link ScopedNetworkEventSource} using the returned instance.
	 */
	public NettyClient getClient() { return this.client; }
	
	/**
	 * Constructs a new instance.
	 */
	public ClientNetworkState()
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
			LOGGER.info("Connection config has been changed: " + msg.payload);
			this.config = (MultiplayerConfig) msg.payload;
			this.configReceived = true;
		});
		
		this.client.registerHandler(NettyCloseEvent.class, msg ->
		{
			this.configReceived = false;
		});
	}
	
	private void onConfigChanged()
	{
		this.getClient().sendMessage(new RemotePlayerConfigMessage(new MultiplayerConfig()));
	}
	
	private String[] f3Log()
	{
		if (!this.client.isActive())
		{
			return new String[]{"Waiting for connection info..."};
		}
		
		if (!this.client.isClosed())
		{
			return new String[]{
					this.client.getRemoteAddress() != null
							? (this.isReady() ? "Connected to server" : "Connecting to server...")
							: MessageFormat.format("Disconnected, attempts left: {0} / {1}", this.client.getReconnectionAttemptsLeft(), NettyClient.RECONNECTION_ATTEMPTS)
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
