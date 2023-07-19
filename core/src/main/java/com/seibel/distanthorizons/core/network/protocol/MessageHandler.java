package com.seibel.distanthorizons.core.network.protocol;

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.messages.CloseMessage;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

@ChannelHandler.Sharable
public class MessageHandler extends SimpleChannelInboundHandler<INetworkMessage>
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private final BiConsumer<INetworkMessage, ChannelHandlerContext> messageConsumer;
	
	public MessageHandler(BiConsumer<INetworkMessage, ChannelHandlerContext> messageConsumer)
	{
		this.messageConsumer = messageConsumer;
	}
	
	@Override
	protected void channelRead0(ChannelHandlerContext channelContext, INetworkMessage message)
	{
		LOGGER.trace("Received message: " + message.getClass().getSimpleName());
		this.messageConsumer.accept(message, channelContext);
	}
	
	@Override
	public void channelInactive(@NotNull ChannelHandlerContext channelContext)
	{
		this.channelRead0(channelContext, new CloseMessage());
	}
	
}
