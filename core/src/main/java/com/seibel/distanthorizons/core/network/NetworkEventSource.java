package com.seibel.distanthorizons.core.network;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.messages.base.CancelMessage;
import com.seibel.distanthorizons.core.network.messages.base.CloseEvent;
import com.seibel.distanthorizons.core.network.messages.base.ExceptionMessage;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.MessageRegistry;
import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public abstract class NetworkEventSource
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	protected final ConcurrentMap<Class<? extends NetworkMessage>, Set<Consumer<NetworkMessage>>> handlers = new ConcurrentHashMap<>();
	private final Table<ChannelHandlerContext, Long, CompletableFuture<FutureTrackableNetworkMessage>> pendingFutures = Tables.synchronizedTable(HashBasedTable.create());
	
	protected boolean hasHandler(Class<? extends NetworkMessage> handlerClass)
	{
		return this.handlers.containsKey(handlerClass);
	}
	
	
	protected void handleMessage(NetworkMessage message)
	{
		boolean handled = false;
		
		if (message instanceof FutureTrackableNetworkMessage)
			((FutureTrackableNetworkMessage) message).futureId |= (long) message.getChannelContext().hashCode() << 32;
		
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
			CompletableFuture<FutureTrackableNetworkMessage> future = pendingFutures.get(message.getChannelContext(), trackableMessage.futureId);
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
			String error = "Unhandled message type: " + message.getClass().getSimpleName();
			if (message instanceof FutureTrackableNetworkMessage)
				error += ", future id: " + ((FutureTrackableNetworkMessage) message).futureId;
			LOGGER.warn(error);
		}
	}
	
	public <T extends NetworkMessage> void registerHandler(Class<T> handlerClass, Consumer<T> handlerImplementation)
	{
		this.handlers.computeIfAbsent(handlerClass, missingHandlerClass ->
				{
					// Will throw if the handler class is not found
					if (handlerClass != CloseEvent.class)
						MessageRegistry.INSTANCE.getMessageId(handlerClass);
					return new HashSet<>();
				})
				.add((Consumer<NetworkMessage>) handlerImplementation);
	}
	
	protected <T extends NetworkMessage> void removeHandler(Class<T> handlerClass, Consumer<T> handlerImplementation)
	{
		this.handlers.computeIfAbsent(handlerClass, missingHandlerClass -> new HashSet<>())
				.remove(handlerImplementation);
	}
	
	
	protected <TResponse extends FutureTrackableNetworkMessage> CompletableFuture<TResponse> sendRequest(ChannelHandlerContext ctx, FutureTrackableNetworkMessage msg)
	{
		msg.futureId |= (long) ctx.hashCode() << 32;
		msg.setChannelContext(ctx);
		
		CompletableFuture<TResponse> responseFuture = new CompletableFuture<>();
		responseFuture.handle((response, throwable) -> {
			if (!(throwable instanceof ChannelException))
				pendingFutures.remove(ctx, msg.futureId);
			
			if (throwable instanceof CancellationException)
				msg.sendResponse(new CancelMessage());
			
			return null;
		});
		pendingFutures.put(ctx, msg.futureId, (CompletableFuture<FutureTrackableNetworkMessage>) responseFuture);
		
		ctx.writeAndFlush(msg).addListener(writeFuture -> {
			if (writeFuture.cause() != null) {
				responseFuture.completeExceptionally(writeFuture.cause());
			}
		});
		return responseFuture;
	}
	
	protected final void completeAllFuturesExceptionally(ChannelHandlerContext ctx, Throwable cause) {
		synchronized (pendingFutures)
		{
			for (CompletableFuture<FutureTrackableNetworkMessage> futureData : pendingFutures.row(ctx).values())
				futureData.completeExceptionally(cause);
			pendingFutures.row(ctx).clear();
		}
	}
	
	protected final void completeAllFuturesExceptionally(Throwable cause) {
		synchronized (pendingFutures)
		{
			for (ChannelHandlerContext ctx : pendingFutures.rowKeySet())
				this.completeAllFuturesExceptionally(ctx, cause);
		}
	}
	
	public void close()
	{
		this.handlers.clear();
		completeAllFuturesExceptionally(new ChannelException(this.getClass().getSimpleName()+" is closed."));
	}
}
