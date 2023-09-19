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

import com.seibel.distanthorizons.core.network.messages.base.CloseEvent;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

@ChannelHandler.Sharable
public class MessageHandler extends SimpleChannelInboundHandler<NetworkMessage>
{
	private static final Logger LOGGER = LogManager.getLogger();
	
	private final Consumer<NetworkMessage> messageConsumer;
	private final Consumer<ChannelHandlerContext> channelActiveConsumer;
	
	public MessageHandler(Consumer<NetworkMessage> messageConsumer, Consumer<ChannelHandlerContext> channelActiveConsumer)
	{
		this.messageConsumer = messageConsumer;
		this.channelActiveConsumer = channelActiveConsumer;
	}
	
	@Override
	protected void channelRead0(ChannelHandlerContext channelContext, NetworkMessage message)
	{
		message.setChannelContext(channelContext);
		LOGGER.trace("Received message: " + message);
		this.messageConsumer.accept(message);
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
	
}
