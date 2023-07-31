package com.seibel.distanthorizons.core.network.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class MessageEncoder extends MessageToByteEncoder<NetworkMessage>
{
	@Override
	protected void encode(ChannelHandlerContext channelContext, NetworkMessage message, ByteBuf outputByteBuf) throws IllegalArgumentException
	{
		outputByteBuf.writeShort(MessageRegistry.INSTANCE.getMessageId(message));
		message.encode(outputByteBuf);
	}
	
}
