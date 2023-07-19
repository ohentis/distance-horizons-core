package com.seibel.distanthorizons.core.network;

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.messages.AckMessage;
import com.seibel.distanthorizons.core.network.protocol.INetworkMessage;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public abstract class NetworkEventSource
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	protected final Map<Class<? extends INetworkMessage>, Set<BiConsumer<INetworkMessage, ChannelHandlerContext>>> handlers = new HashMap<>();
	
	
	protected boolean hasHandler(Class<? extends INetworkMessage> handlerClass)
	{
		return this.handlers.containsKey(handlerClass);
	}
	
	protected void handleMessage(INetworkMessage message, ChannelHandlerContext channelContext)
	{
		Set<BiConsumer<INetworkMessage, ChannelHandlerContext>> handlerList = this.handlers.get(message.getClass());
		if (handlerList == null || handlerList.isEmpty())
		{
			LOGGER.warn("Unhandled message type: " + message.getClass().getSimpleName());
			return;
		}
		
		for (BiConsumer<INetworkMessage, ChannelHandlerContext> handler : handlerList)
		{
			handler.accept(message, channelContext);
		}
	}
	
	public <T extends INetworkMessage> void registerHandler(Class<T> handlerClass, BiConsumer<T, ChannelHandlerContext> handlerImplementation)
	{
		this.handlers.computeIfAbsent(handlerClass, missingHandlerClass -> new HashSet<>())
				.add((BiConsumer<INetworkMessage, ChannelHandlerContext>) handlerImplementation);
	}
	
	public <T extends INetworkMessage> void registerAckHandler(Class<T> clazz, Consumer<ChannelHandlerContext> handler)
	{
		this.registerHandler(AckMessage.class, (ackMessage, channelContext) ->
		{
			if (ackMessage.messageType == clazz)
			{
				handler.accept(channelContext);
			}
		});
	}
	
	protected <T extends INetworkMessage> void removeHandler(Class<T> handlerClass, BiConsumer<T, ChannelHandlerContext> handlerImplementation)
	{
		this.handlers.computeIfAbsent(handlerClass, missingHandlerClass -> new HashSet<>())
				.remove(handlerImplementation);
	}
	
	public void close()
	{
		this.handlers.clear();
	}
}
