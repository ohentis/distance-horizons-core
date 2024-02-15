/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2023 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.network.protocol;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.messages.base.CloseEvent;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;

import java.net.SocketException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@ChannelHandler.Sharable
public class MessageHandler extends SimpleChannelInboundHandler<NetworkMessage>
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	private final BiConsumer<ChannelHandlerContext, NetworkMessage> messageConsumer;
	private final Consumer<ChannelHandlerContext> channelActiveConsumer;
	private final BiConsumer<ChannelHandlerContext, Throwable> closeReasonConsumer;
	
	public MessageHandler(
			BiConsumer<ChannelHandlerContext, NetworkMessage> messageConsumer,
			Consumer<ChannelHandlerContext> channelActiveConsumer,
			BiConsumer<ChannelHandlerContext, Throwable> closeReasonConsumer)
	{
		this.messageConsumer = messageConsumer;
		this.channelActiveConsumer = channelActiveConsumer;
		this.closeReasonConsumer = closeReasonConsumer;
	}
	
	@Override
	protected void channelRead0(ChannelHandlerContext channelContext, NetworkMessage message)
	{
		LOGGER.debug("Received message: " + message);
		this.messageConsumer.accept(channelContext, message);
	}
	
	@Override
	public void channelActive(@NotNull ChannelHandlerContext ctx) throws Exception
	{
		super.channelActive(ctx);
		this.channelActiveConsumer.accept(ctx);
	}
	
	@Override
	public void channelInactive(@NotNull ChannelHandlerContext channelContext) throws Exception
	{
		super.channelInactive(channelContext);
		this.channelRead0(channelContext, new CloseEvent());
	}
	
	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
	{
		this.closeReasonConsumer.accept(ctx, cause);
		ctx.close();
		
		if (cause instanceof SocketException)
		{
			LOGGER.info("Exception caught in channel: [" + ctx.name() + "]: " + cause.getMessage());
		}
		else
		{
			LOGGER.error("Exception caught in channel: [" + ctx.name() + "].", cause);
		}
	}
	
}
