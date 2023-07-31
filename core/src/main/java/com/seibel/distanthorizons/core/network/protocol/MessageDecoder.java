package com.seibel.distanthorizons.core.network.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public class MessageDecoder extends ByteToMessageDecoder 
{
    @Override
    protected void decode(ChannelHandlerContext channelContext, ByteBuf inputByteBuf, List<Object> outputDecodedObjectList) 
	{
        NetworkMessage message = MessageRegistry.INSTANCE.createMessage(inputByteBuf.readShort());
        outputDecodedObjectList.add(INetworkObject.decode(message, inputByteBuf));
    }
	
}
