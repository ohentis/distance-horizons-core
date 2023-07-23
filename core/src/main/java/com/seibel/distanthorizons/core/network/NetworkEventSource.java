package com.seibel.distanthorizons.core.network;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.messages.AckMessage;
import com.seibel.distanthorizons.core.network.protocol.INetworkMessage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public abstract class NetworkEventSource
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	protected final Map<Class<? extends INetworkMessage>, Set<BiConsumer<INetworkMessage, ChannelHandlerContext>>> handlers = new HashMap<>();
	private final Table<ChannelHandlerContext, Integer, CompletableFuture<FutureTrackableNetworkMessage>> pendingFutures = HashBasedTable.create();
	
	protected boolean hasHandler(Class<? extends INetworkMessage> handlerClass)
	{
		return this.handlers.containsKey(handlerClass);
	}
	
	protected void handleMessage(INetworkMessage message, ChannelHandlerContext channelContext)
	{
		boolean handled = false;
		
		Set<BiConsumer<INetworkMessage, ChannelHandlerContext>> handlerList = this.handlers.get(message.getClass());
		if (handlerList != null)
		{
			for (BiConsumer<INetworkMessage, ChannelHandlerContext> handler : handlerList)
			{
				handled = true;
				handler.accept(message, channelContext);
			}
		}
		
		if (message instanceof FutureTrackableNetworkMessage)
		{
			FutureTrackableNetworkMessage trackableMessage = (FutureTrackableNetworkMessage)message;
			CompletableFuture<FutureTrackableNetworkMessage> future = pendingFutures.remove(channelContext, trackableMessage.futureId);
			if (future != null)
			{
				handled = true;
				future.complete(trackableMessage);
			}
		}
		
		if (!handled)
		{
			LOGGER.warn("Unhandled message type: " + message.getClass().getSimpleName());
		}
	}
	
	public <T extends INetworkMessage> void registerHandler(Class<T> handlerClass, BiConsumer<T, ChannelHandlerContext> handlerImplementation)
	{
		this.handlers.computeIfAbsent(handlerClass, missingHandlerClass -> new HashSet<>())
				.add((BiConsumer<INetworkMessage, ChannelHandlerContext>) handlerImplementation);
	}
	
	public <T extends INetworkMessage> void registerAckHandler(Class<T> clazz, Consumer<ChannelHandlerContext> handler)
	{
		this.registerHandler(AckMessage.class, (ackMessage, channelContext) ->
		{
			if (ackMessage.messageType == clazz)
			{
				handler.accept(channelContext);
			}
		});
	}
	
	protected <T extends INetworkMessage> void removeHandler(Class<T> handlerClass, BiConsumer<T, ChannelHandlerContext> handlerImplementation)
	{
		this.handlers.computeIfAbsent(handlerClass, missingHandlerClass -> new HashSet<>())
				.remove(handlerImplementation);
	}
	
	protected <TResponse extends FutureTrackableNetworkMessage> CompletableFuture<TResponse> sendRequest(ChannelHandlerContext ctx, FutureTrackableNetworkMessage msg)
	{
		CompletableFuture<TResponse> responseFuture = new CompletableFuture<>();
		pendingFutures.put(ctx, msg.futureId, (CompletableFuture<FutureTrackableNetworkMessage>) responseFuture);
		
		ctx.writeAndFlush(msg).addListener(writeFuture -> {
			if (writeFuture.cause() != null) {
				responseFuture.completeExceptionally(writeFuture.cause());
			}
		});
		return responseFuture;
	}
	
	protected void completeAllFuturesExceptionally(ChannelHandlerContext ctx, Throwable cause) {
		for (CompletableFuture<FutureTrackableNetworkMessage> futureData : pendingFutures.row(ctx).values())
			futureData.completeExceptionally(cause);
		pendingFutures.row(ctx).clear();
	}
	
	protected void completeAllFuturesExceptionally(Throwable cause) {
		for (ChannelHandlerContext ctx : pendingFutures.rowKeySet())
			this.completeAllFuturesExceptionally(ctx, cause);
	}
	
	public void close()
	{
		this.handlers.clear();
		completeAllFuturesExceptionally(new Exception(this.getClass().getSimpleName()+" is closed."));
	}
}
