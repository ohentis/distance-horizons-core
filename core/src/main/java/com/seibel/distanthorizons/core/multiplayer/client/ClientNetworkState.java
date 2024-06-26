package com.seibel.distanthorizons.core.multiplayer.client;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfig;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfigChangeListener;
import com.seibel.distanthorizons.core.network.event.ScopedNetworkEventSource;
import com.seibel.distanthorizons.core.network.event.PluginCloseEvent;
import com.seibel.distanthorizons.core.network.messages.base.RemotePlayerConfigMessage;
import com.seibel.distanthorizons.core.network.session.Session;
import org.apache.logging.log4j.LogManager;

import java.io.Closeable;
import java.util.List;

public class ClientNetworkState implements Closeable
{
	protected static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	private final Session session = new Session(null);
	private EServerSupportStatus serverSupportStatus = EServerSupportStatus.NONE;
	
	
	public MultiplayerConfig config = new MultiplayerConfig();
	private volatile boolean configReceived = false;
	private final MultiplayerConfigChangeListener configChangeListener = new MultiplayerConfigChangeListener(this::sendConfigMessage);
	public boolean isReady() { return this.configReceived; }
	
	/**
	 * Returns the client used by this instance. <p>
	 * If you need to subscribe to any packet events, create an instance of {@link ScopedNetworkEventSource} using the returned instance.
	 */
	public Session getSession() { return this.session; }
	
	/**
	 * Constructs a new instance.
	 */
	public ClientNetworkState()
	{
		this.session.registerHandler(RemotePlayerConfigMessage.class, msg ->
		{
			this.serverSupportStatus = EServerSupportStatus.FULL;
			
			LOGGER.info("Connection config has been changed: " + msg.payload);
			this.config = (MultiplayerConfig) msg.payload;
			this.configReceived = true;
		});
		
		this.session.registerHandler(PluginCloseEvent.class, msg ->
		{
			this.configReceived = false;
		});
	}
	
	public void sendConfigMessage()
	{
		this.configReceived = false;
		this.getSession().sendMessage(new RemotePlayerConfigMessage(new MultiplayerConfig()));
	}
	
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		if (this.session.isClosed())
		{
			messageList.add("Session closed: " + this.session.getCloseReason().getMessage());
			return;
		}
		
		messageList.add(this.serverSupportStatus.message);
	}
	
	@Override
	public void close()
	{
		this.configChangeListener.close();
		this.session.close();
	}
	
	private enum EServerSupportStatus
	{
		NONE("Server does not support DH"),
		LEVELS_ONLY("Server supports shared level keys"),
		FULL("Server has full DH support");
		
		public final String message;
		
		EServerSupportStatus(String message)
		{
			this.message = message;
		}
	}
}