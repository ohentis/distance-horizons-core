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

import com.seibel.distanthorizons.core.network.messages.netty.NettyMessageRegistry;
import com.seibel.distanthorizons.core.network.netty.NettyMessage;
import com.seibel.distanthorizons.core.network.protocol.MessageDecoder;
import com.seibel.distanthorizons.core.network.protocol.MessageEncoder;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.flush.FlushConsolidationHandler;
import org.jetbrains.annotations.NotNull;

/** Used when creating a network channel */
public class NettyChannelInitializer extends ChannelInitializer<SocketChannel>
{
	/**
	 * 4 MiB should be enough for any transferred data. <br>
	 * Currently largest transferred data is DH full data sections, which usually don't exceed 1-2 MiB in size.
	 */
	private static final int MAX_MESSAGE_LENGTH = 4194304;
	
	private final NettyMessageHandler messageHandler;
	
	public NettyChannelInitializer(NettyMessageHandler messageHandler) { this.messageHandler = messageHandler; }
	
	@Override
	public void initChannel(@NotNull SocketChannel socketChannel)
	{
		ChannelPipeline pipeline = socketChannel.pipeline();
		
		// Encoder
		pipeline.addLast(new FlushConsolidationHandler(256, true));
		pipeline.addLast(new LengthFieldPrepender(Integer.BYTES));
		pipeline.addLast(new MessageEncoder<>(NettyMessageRegistry.INSTANCE, NettyMessage.class));
		pipeline.addLast(new NettyOutboundExceptionRouter());
		
		// Decoder
		pipeline.addLast(new LengthFieldBasedFrameDecoder(MAX_MESSAGE_LENGTH, 0, Integer.BYTES, 0, Integer.BYTES));
		pipeline.addLast(new MessageDecoder<>(NettyMessageRegistry.INSTANCE));
		
		// Handler
		pipeline.addLast(this.messageHandler);
	}
	
}
