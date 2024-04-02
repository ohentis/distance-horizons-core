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

package com.seibel.distanthorizons.core.network.protocol.netty;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.messages.netty.base.NettyCloseEvent;
import com.seibel.distanthorizons.core.network.netty.NettyMessage;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;

import java.net.SocketException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@ChannelHandler.Sharable
public class NettyMessageHandler extends SimpleChannelInboundHandler<NettyMessage>
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	private final BiConsumer<ChannelHandlerContext, NettyMessage> messageConsumer;
	private final Consumer<ChannelHandlerContext> channelActiveConsumer;
	private final BiConsumer<ChannelHandlerContext, Throwable> exceptionConsumer;
	
	public NettyMessageHandler(
			BiConsumer<ChannelHandlerContext, NettyMessage> messageConsumer,
			Consumer<ChannelHandlerContext> channelActiveConsumer,
			BiConsumer<ChannelHandlerContext, Throwable> exceptionConsumer)
	{
		this.messageConsumer = messageConsumer;
		this.channelActiveConsumer = channelActiveConsumer;
		this.exceptionConsumer = exceptionConsumer;
	}
	
	@Override
	protected void channelRead0(ChannelHandlerContext channelContext, NettyMessage message)
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
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
	{
		if (cause instanceof SocketException)
		{
			LOGGER.info("Exception caught in channel: [" + ctx.name() + "]: " + cause.getMessage());
		}
		else
		{
			LOGGER.error("Exception caught in channel: [" + ctx.name() + "].", cause);
			this.exceptionConsumer.accept(ctx, cause);
		}
		
		ctx.close();
	}
	
	@Override
	public void channelInactive(@NotNull ChannelHandlerContext channelContext) throws Exception
	{
		super.channelInactive(channelContext);
		this.channelRead0(channelContext, new NettyCloseEvent());
	}
	
}
