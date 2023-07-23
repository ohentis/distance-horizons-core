package com.seibel.distanthorizons.core.network.protocol;

import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import org.jetbrains.annotations.NotNull;

/** used when creating a network channel */
public class NetworkChannelInitializer extends ChannelInitializer<SocketChannel> 
{
    private final MessageHandler messageHandler;
	
	
	
    public NetworkChannelInitializer(MessageHandler messageHandler) { this.messageHandler = messageHandler; }
	
    @Override
    public void initChannel(@NotNull SocketChannel socketChannel) 
	{
        ChannelPipeline pipeline = socketChannel.pipeline();
		
        // Encoder
        pipeline.addLast(new LengthFieldPrepender(Integer.BYTES));
        pipeline.addLast(new MessageEncoder());
        pipeline.addLast(new NetworkOutboundExceptionRouter());
		
        // Decoder
        pipeline.addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, Integer.BYTES, 0, Integer.BYTES));
        pipeline.addLast(new MessageDecoder());
		
        // Handler
        pipeline.addLast(this.messageHandler);
        pipeline.addLast(new NetworkExceptionHandler());
    }
	
}
