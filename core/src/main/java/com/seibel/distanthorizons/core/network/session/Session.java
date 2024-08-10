package com.seibel.distanthorizons.core.network.session;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.event.NetworkEventSource;
import com.seibel.distanthorizons.core.network.event.internal.CloseEvent;
import com.seibel.distanthorizons.core.network.event.internal.ProtocolErrorEvent;
import com.seibel.distanthorizons.core.network.messages.NetworkMessage;
import com.seibel.distanthorizons.core.network.messages.TrackableMessage;
import com.seibel.distanthorizons.core.network.messages.base.CloseReasonMessage;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class Session extends NetworkEventSource
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	private static final IPluginPacketSender PACKET_SENDER = SingletonInjector.INSTANCE.get(IPluginPacketSender.class);
	
	private static final AtomicInteger lastId = new AtomicInteger();
	public final int id = lastId.getAndIncrement();

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
		
		this.registerHandler(CloseReasonMessage.class, msg ->
		{
			this.close(new SessionClosedException(msg.reason));
		});
		
		this.registerHandler(ProtocolErrorEvent.class, event ->
		{
			if (event.replyWithCloseReason)
			{
				this.sendMessage(new CloseReasonMessage("Internal error on other side"));
			}
			
			this.close(event.reason);
		});
	}
	
	
	public void tryHandleMessage(NetworkMessage message)
	{
		if (this.closeReason.get() != null)
		{
			return;
		}
		
		message.setSession(this);
		
		try
		{
			LOGGER.debug("Received message: {}", message);
			this.handleMessage(message);
		}
		catch (Throwable e)
		{
			LOGGER.error("Failed to handle the message. New messages will be ignored.", e);
			LOGGER.error("Message: {}", message);
			this.close();
		}
	}
	
	@Override
	public <T extends NetworkMessage> void registerHandler(Class<T> handlerClass, Consumer<T> handlerImplementation)
	{
		if (this.closeReason.get() != null)
		{
			return;
		}
		
		this.registerHandler(this, handlerClass, handlerImplementation);
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
		if (this.closeReason.get() != null)
		{
			return;
		}
		
		LOGGER.debug("Sending message: {}", message);
		message.setSession(this);
		
		try
		{
			if (this.serverPlayer != null)
			{
				PACKET_SENDER.sendPluginPacketServer(this.serverPlayer, message);
			}
			else
			{
				PACKET_SENDER.sendPluginPacketClient(message);
			}
		}
		catch (Throwable throwable)
		{
			LOGGER.info("Failed to send a message", throwable);
			LOGGER.info("Message: {}", message);
			this.close(throwable);
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
			this.handleMessage(new CloseEvent());
		}
		catch (Throwable ignored)
		{
		}
		
		super.close();
	}
	
}