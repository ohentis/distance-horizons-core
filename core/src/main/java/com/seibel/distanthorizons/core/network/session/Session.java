package com.seibel.distanthorizons.core.network.session;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.event.NetworkEventSource;
import com.seibel.distanthorizons.core.network.event.PluginCloseEvent;
import com.seibel.distanthorizons.core.network.messages.NetworkMessage;
import com.seibel.distanthorizons.core.network.messages.TrackableMessage;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class Session extends NetworkEventSource
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	private static final IPluginPacketSender PACKET_SENDER = SingletonInjector.INSTANCE.get(IPluginPacketSender.class);
	
	/**
	 * When non-null, any received data will be ignored. <br>
	 * This does not include wrong versions, which are ignored without setting this flag,
	 * to allow multi-compat servers.
	 */
	private final AtomicReference<Throwable> closeReason = new AtomicReference<>();
	public Throwable getCloseReason() { return this.closeReason.get(); }
	public boolean isClosed() { return this.closeReason.get() != null; }
	
	@Nullable
	public final IServerPlayerWrapper serverPlayer;
	
	public Session(@Nullable IServerPlayerWrapper serverPlayer)
	{
		this.serverPlayer = serverPlayer;
	}
	
	
	public void tryHandleMessage(NetworkMessage message)
	{
		if (this.closeReason.get() != null)
		{
			return;
		}
		
		try
		{
			LOGGER.debug("Received message: {}", message);
			this.handleMessage(message);
		}
		catch (Throwable e)
		{
			LOGGER.error("Failed to handle the message. New messages will be ignored.", e);
			LOGGER.error("Message: " + message);
			this.close();
		}
	}
	
	public <TResponse extends TrackableMessage> CompletableFuture<TResponse> sendRequest(TrackableMessage msg, Class<TResponse> responseClass)
	{
		msg.setSession(this);
		CompletableFuture<TResponse> responseFuture = this.createRequest(msg, responseClass);
		this.sendMessage(msg);
		return responseFuture;
	}
	
	public void sendMessage(NetworkMessage message)
	{
		LOGGER.debug("Sending message: {}", message);
		
		if (this.serverPlayer != null)
		{
			PACKET_SENDER.sendPluginPacketServer(this.serverPlayer, message);
		}
		else
		{
			PACKET_SENDER.sendPluginPacketClient(message);
		}
	}
	
	public void close(Throwable closeReason)
	{
		if (!this.closeReason.compareAndSet(null, closeReason))
		{
			return;
		}
		
		try
		{
			this.handleMessage(new PluginCloseEvent());
		}
		catch (Throwable ignored)
		{
		}
		
		super.close();
	}
	
}