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

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.messages.base.CancelMessage;
import com.seibel.distanthorizons.core.network.messages.base.CloseEvent;
import com.seibel.distanthorizons.core.network.messages.base.ExceptionMessage;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.MessageRegistry;
import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;
import io.netty.channel.ChannelException;
import org.apache.logging.log4j.Logger;

import java.io.InvalidClassException;
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
	private final ConcurrentMap<IConnection, ConcurrentMap<Long, FutureResponseData>> pendingFutures = new ConcurrentHashMap<>();
	
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
			ConcurrentMap<Long, FutureResponseData> subMap = pendingFutures.get(message.getConnection());
			if (subMap != null)
			{
				FutureResponseData responseData = subMap.get(trackableMessage.futureId);
				if (responseData != null)
				{
					handled = true;
					
					if (message instanceof ExceptionMessage)
						responseData.future.completeExceptionally(((ExceptionMessage) message).exception);
					else if (message.getClass() != responseData.responseClass)
						responseData.future.completeExceptionally(new InvalidClassException("Response with invalid type: expected " + responseData.responseClass.getSimpleName() + ", got:" + message));
					else
						responseData.future.complete(trackableMessage);
				}
			}
		}
		
		if (!handled && message.warnWhenUnhandled())
			LOGGER.warn("Unhandled message: " + message);
	}
	
	protected void addNewConnection(IConnection connection)
	{
		this.pendingFutures.put(connection, new ConcurrentHashMap<>());
	}
	
	public <T extends NetworkMessage> void registerHandler(Class<T> handlerClass, Consumer<T> handlerImplementation)
	{
		//noinspection unchecked
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
	
	
	protected <TResponse extends FutureTrackableNetworkMessage> CompletableFuture<TResponse> sendRequest(IConnection connection, FutureTrackableNetworkMessage msg, Class<TResponse> responseClass)
	{
		msg.setConnection(connection);
		
		CompletableFuture<TResponse> responseFuture = new CompletableFuture<>();
		responseFuture.whenComplete((response, throwable) ->
		{
			if (!(throwable instanceof ChannelException))
			{
				ConcurrentMap<Long, FutureResponseData> subMap = pendingFutures.get(connection);
				if (subMap != null)
					subMap.remove(msg.futureId);
			}
			
			if (throwable instanceof CancellationException)
				msg.sendResponse(new CancelMessage());
		});
		
		ConcurrentMap<Long, FutureResponseData> subMap = pendingFutures.get(connection);
		if (subMap == null)
		{
			// Was deleted before adding
			responseFuture.completeExceptionally(connection.getCloseReason());
			return responseFuture;
		}
		subMap.put(msg.futureId, new FutureResponseData(responseClass, responseFuture));
		if (!pendingFutures.containsKey(connection))
		{
			// Was deleted while adding
			responseFuture.completeExceptionally(connection.getCloseReason());
			return responseFuture;
		}
		// If passed until here, cancelling is up to the cleaning side
		
		connection.sendMessage(msg).whenComplete((ignored, throwable) ->
		{
			if (throwable != null)
				responseFuture.completeExceptionally(throwable);
		});
		return responseFuture;
	}
	
	protected final void completeAllFuturesExceptionally(IConnection connection, Throwable cause)
	{
		ConcurrentMap<Long, FutureResponseData> map = pendingFutures.remove(connection);
		if (map == null) return;
		
		for (FutureResponseData responseData : map.values())
			responseData.future.completeExceptionally(cause);
	}
	
	protected final void completeAllFuturesExceptionally(Throwable cause)
	{
		for (IConnection connection : pendingFutures.keySet())
			this.completeAllFuturesExceptionally(connection, cause);
	}
	
	public void close()
	{
		this.handlers.clear();
		completeAllFuturesExceptionally(new ChannelException(this.getClass().getSimpleName()+" is closed."));
	}
	
	private static class FutureResponseData
	{
		public final Class<? extends FutureTrackableNetworkMessage> responseClass;
		public final CompletableFuture<FutureTrackableNetworkMessage> future;
		
		private <T extends FutureTrackableNetworkMessage> FutureResponseData(Class<T> responseClass, CompletableFuture<T> future) {
			this.responseClass = responseClass;
			//noinspection unchecked
			this.future = (CompletableFuture<FutureTrackableNetworkMessage>) future;
		}
	}
}
