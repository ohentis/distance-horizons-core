package com.seibel.distanthorizons.core.network;

import com.seibel.distanthorizons.core.network.messages.base.CloseReasonMessage;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;

public interface IConnection
{
	Logger LOGGER = LogManager.getLogger();
	
	ChannelHandlerContext getChannelContext();
	NetworkEventSource getRequestHandler();
	
	default SocketAddress getRemoteAddress()
	{
		return this.getChannelContext().channel().remoteAddress();
	}
	
	default CompletableFuture<Void> sendMessage(NetworkMessage message)
	{
		LOGGER.trace("Sending message: " + message);
		CompletableFuture<Void> future = new CompletableFuture<>();
		
		ChannelHandlerContext ctx = this.getChannelContext();
		if (ctx == null)
		{
			future.completeExceptionally(new ChannelException("Channel is closed."));
			return future;
		}
		
		ctx.writeAndFlush(message).addListener(writeFuture ->
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
		CompletableFuture<TResponse> responseFuture = this.getRequestHandler().createRequest(this, msg, responseClass);
		this.sendMessage(msg).whenComplete((ignored, throwable) ->
		{
			if (throwable != null)
			{
				responseFuture.completeExceptionally(throwable);
			}
		});
		return responseFuture;
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
