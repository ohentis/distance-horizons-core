package com.seibel.distanthorizons.core.network;

import com.seibel.distanthorizons.core.network.messages.base.CloseReasonMessage;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;

public interface IConnection
{
	ChannelHandlerContext getChannelContext();
	NetworkEventSource getRequestHandler();
	
	default SocketAddress getRemoteAddress()
	{
		return this.getChannelContext().channel().remoteAddress();
	}
	
	default CompletableFuture<Void> sendMessage(NetworkMessage message)
	{
		CompletableFuture<Void> future = new CompletableFuture<>();
		
		this.getChannelContext().writeAndFlush(message).addListener(writeFuture ->
		{
			if (writeFuture.cause() != null)
			{
				future.completeExceptionally(writeFuture.cause());
			}
			else
			{
				future.complete(null);
			}
		});
		
		return future;
	}
	
	default <TResponse extends FutureTrackableNetworkMessage> CompletableFuture<TResponse> sendRequest(FutureTrackableNetworkMessage msg, Class<TResponse> responseClass)
	{
		return this.getRequestHandler().sendRequest(this, msg, responseClass);
	}
	
	default void disconnect(String reason)
	{
		this.getChannelContext().channel().config().setAutoRead(false);
		this.getChannelContext().writeAndFlush(new CloseReasonMessage(reason))
				.addListener(ChannelFutureListener.CLOSE);
	}
	
	default Throwable getCloseReason()
	{
		return this.getChannelContext().channel().closeFuture().cause();
	}
	
}
