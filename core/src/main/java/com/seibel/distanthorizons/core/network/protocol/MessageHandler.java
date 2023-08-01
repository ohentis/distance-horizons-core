package com.seibel.distanthorizons.core.network.protocol;

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.messages.CloseMessage;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

@ChannelHandler.Sharable
public class MessageHandler extends SimpleChannelInboundHandler<NetworkMessage>
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private final Consumer<NetworkMessage> messageConsumer;
	
	public MessageHandler(Consumer<NetworkMessage> messageConsumer)
	{
		this.messageConsumer = messageConsumer;
	}
	
	@Override
	protected void channelRead0(ChannelHandlerContext channelContext, NetworkMessage message)
	{
		LOGGER.trace("Received message: " + message.getClass().getSimpleName());
		message.setChannelContext(channelContext);
		this.messageConsumer.accept(message);
	}
	
	@Override
	public void channelInactive(@NotNull ChannelHandlerContext channelContext)
	{
		this.channelRead0(channelContext, new CloseMessage());
	}
	
}
