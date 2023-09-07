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

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.messages.base.CloseEvent;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

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
		this.channelRead0(channelContext, new CloseEvent());
	}
	
}
