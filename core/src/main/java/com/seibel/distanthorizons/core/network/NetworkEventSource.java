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
	private final ConcurrentMap<ChannelHandlerContext, ConcurrentMap<Long, CompletableFuture<FutureTrackableNetworkMessage>>> pendingFutures = new ConcurrentHashMap<>();
	
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
			ConcurrentMap<Long, CompletableFuture<FutureTrackableNetworkMessage>> subMap = pendingFutures.get(message.getChannelContext());
			if (subMap != null)
			{
				CompletableFuture<FutureTrackableNetworkMessage> future = subMap.get(trackableMessage.futureId);
				if (future != null)
				{
					handled = true;
					
					if (message instanceof ExceptionMessage)
						future.completeExceptionally(((ExceptionMessage) message).exception);
					else
						future.complete(trackableMessage);
				}
			}
		}
		
		if (!handled && message.warnWhenUnhandled())
			LOGGER.warn("Unhandled message: " + message);
	}
	
	protected void addNewContext(ChannelHandlerContext ctx)
	{
		this.pendingFutures.put(ctx, new ConcurrentHashMap<>());
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
		msg.setChannelContext(ctx);
		
		CompletableFuture<TResponse> responseFuture = new CompletableFuture<>();
		responseFuture.handle((response, throwable) -> {
			if (!(throwable instanceof ChannelException))
			{
				ConcurrentMap<Long, CompletableFuture<FutureTrackableNetworkMessage>> subMap = pendingFutures.get(ctx);
				if (subMap != null)
					subMap.remove(msg.futureId);
			}
			
			if (throwable instanceof CancellationException)
				msg.sendResponse(new CancelMessage());
			
			return null;
		});
		
		ConcurrentMap<Long, CompletableFuture<FutureTrackableNetworkMessage>> subMap = pendingFutures.get(ctx);
		if (subMap == null) {
			// Was deleted before adding
			responseFuture.completeExceptionally(ctx.channel().closeFuture().cause());
			return responseFuture;
		}
		//noinspection unchecked
		subMap.put(msg.futureId, (CompletableFuture<FutureTrackableNetworkMessage>) responseFuture);
		if (!pendingFutures.containsKey(ctx)) {
			// Was deleted while adding
			responseFuture.completeExceptionally(ctx.channel().closeFuture().cause());
			return responseFuture;
		}
		// If passed until here, cancelling is up to the cleaning side
		
		ctx.writeAndFlush(msg).addListener(writeFuture -> {
			if (writeFuture.cause() != null) {
				responseFuture.completeExceptionally(writeFuture.cause());
			}
		});
		return responseFuture;
	}
	
	protected final void completeAllFuturesExceptionally(ChannelHandlerContext ctx, Throwable cause)
	{
		ConcurrentMap<Long, CompletableFuture<FutureTrackableNetworkMessage>> map = pendingFutures.remove(ctx);
		if (map == null) return;
		
		for (CompletableFuture<FutureTrackableNetworkMessage> future : map.values())
			future.completeExceptionally(cause);
	}
	
	protected final void completeAllFuturesExceptionally(Throwable cause)
	{
		for (ChannelHandlerContext ctx : pendingFutures.keySet())
			this.completeAllFuturesExceptionally(ctx, cause);
	}
	
	public void close()
	{
		this.handlers.clear();
		completeAllFuturesExceptionally(new ChannelException(this.getClass().getSimpleName()+" is closed."));
	}
}
