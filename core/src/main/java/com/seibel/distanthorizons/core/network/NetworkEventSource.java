package com.seibel.distanthorizons.core.network;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.messages.ExceptionMessage;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public abstract class NetworkEventSource
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	protected final Map<Class<? extends NetworkMessage>, Set<Consumer<NetworkMessage>>> handlers = new HashMap<>();
	private final Table<ChannelHandlerContext, Integer, CompletableFuture<FutureTrackableNetworkMessage>> pendingFutures = HashBasedTable.create();
	
	protected boolean hasHandler(Class<? extends NetworkMessage> handlerClass)
	{
		return this.handlers.containsKey(handlerClass);
	}
	
	
	protected void handleMessage(NetworkMessage message)
	{
		boolean handled = false;
		
		Set<Consumer<NetworkMessage>> handlerList = this.handlers.get(message.getClass());
		if (handlerList != null)
		{
			for (Consumer<NetworkMessage> handler : handlerList)
			{
				handled = true;
				handler.accept(message);
			}
		}
		
		if (message instanceof FutureTrackableNetworkMessage)
		{
			FutureTrackableNetworkMessage trackableMessage = (FutureTrackableNetworkMessage)message;
			CompletableFuture<FutureTrackableNetworkMessage> future = pendingFutures.remove(message.getChannelContext(), trackableMessage.futureId);
			if (future != null)
			{
				handled = true;
				
				if (message instanceof ExceptionMessage)
					future.completeExceptionally(((ExceptionMessage) message).exception);
				else
					future.complete(trackableMessage);
			}
		}
		
		if (!handled)
		{
			LOGGER.warn("Unhandled message type: " + message.getClass().getSimpleName());
		}
	}
	
	public <T extends NetworkMessage> void registerHandler(Class<T> handlerClass, Consumer<T> handlerImplementation)
	{
		this.handlers.computeIfAbsent(handlerClass, missingHandlerClass -> new HashSet<>())
				.add((Consumer<NetworkMessage>) handlerImplementation);
	}
	
	protected <T extends NetworkMessage> void removeHandler(Class<T> handlerClass, Consumer<T> handlerImplementation)
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
	
	protected final void completeAllFuturesExceptionally(ChannelHandlerContext ctx, Throwable cause) {
		for (CompletableFuture<FutureTrackableNetworkMessage> futureData : pendingFutures.row(ctx).values())
			futureData.completeExceptionally(cause);
		pendingFutures.row(ctx).clear();
	}
	
	protected final void completeAllFuturesExceptionally(Throwable cause) {
		for (ChannelHandlerContext ctx : pendingFutures.rowKeySet())
			this.completeAllFuturesExceptionally(ctx, cause);
	}
	
	public void close()
	{
		this.handlers.clear();
		completeAllFuturesExceptionally(new Exception(this.getClass().getSimpleName()+" is closed."));
	}
}
