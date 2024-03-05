package com.seibel.distanthorizons.core.network.netty;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.messages.netty.base.CloseReasonMessage;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;

import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;

public interface INettyConnection
{
	ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	@Nullable
	ChannelHandlerContext getChannelContext();
	NettyEventSource getRequestHandler();
	@Nullable
	Throwable getCloseReason();
	
	@Nullable
	default SocketAddress getRemoteAddress()
	{
		ChannelHandlerContext ctx = this.getChannelContext();
		if (ctx == null)
		{
			return null;
		}
		
		return ctx.channel().remoteAddress();
	}
	
	default CompletableFuture<Void> sendMessage(NettyMessage message)
	{
		LOGGER.debug("Sending message: " + message);
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
	
	default <TResponse extends TrackableNettyMessage> CompletableFuture<TResponse> sendRequest(TrackableNettyMessage msg, Class<TResponse> responseClass)
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
		ChannelHandlerContext ctx = this.getChannelContext();
		if (ctx == null)
		{
			return;
		}
		
		ctx.channel().config().setAutoRead(false);
		ctx.writeAndFlush(new CloseReasonMessage(reason))
				.addListener(ChannelFutureListener.CLOSE);
	}
	
}
