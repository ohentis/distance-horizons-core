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

package com.seibel.distanthorizons.core.network.event;

import com.google.common.cache.CacheBuilder;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.messages.NetworkMessage;
import com.seibel.distanthorizons.core.network.messages.TrackableMessage;
import com.seibel.distanthorizons.core.network.messages.MessageRegistry;
import com.seibel.distanthorizons.core.network.session.SessionClosedException;
import com.seibel.distanthorizons.core.network.messages.requests.CancelMessage;
import com.seibel.distanthorizons.core.network.messages.requests.ExceptionMessage;
import com.seibel.distanthorizons.coreapi.ModInfo;
import org.apache.logging.log4j.LogManager;

import java.io.InvalidClassException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

public abstract class NetworkEventSource
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	protected final ConcurrentMap<
			Class<? extends NetworkMessage>,
			ConcurrentMap<
					NetworkEventSource,
					Set<Consumer<NetworkMessage>>
			>
	> handlers = new ConcurrentHashMap<>();
	
	private final ConcurrentMap<Long, FutureResponseData> pendingFutures = new ConcurrentHashMap<>();
	private final Set<Long> cancelledFutures = Collections.newSetFromMap(CacheBuilder.newBuilder()
			.expireAfterWrite(10, TimeUnit.SECONDS)
			.<Long, Boolean>build()
			.asMap());
	
	
	protected void handleMessage(NetworkMessage message)
	{
		boolean handled = false;
		
		ConcurrentMap<NetworkEventSource, Set<Consumer<NetworkMessage>>> handlersByEventSource = this.handlers.get(message.getClass());
		if (handlersByEventSource != null)
		{
			for (Set<Consumer<NetworkMessage>> handlerSet : handlersByEventSource.values())
			{
				for (Consumer<NetworkMessage> handler : handlerSet)
				{
					handled = true;
					handler.accept(message);
				}
			}
		}
		
		if (message instanceof TrackableMessage)
		{
			TrackableMessage trackableMessage = (TrackableMessage) message;
			
			FutureResponseData responseData = this.pendingFutures.get(trackableMessage.futureId);
			if (responseData != null)
			{
				handled = true;
				
				if (message instanceof ExceptionMessage)
				{
					responseData.future.completeExceptionally(((ExceptionMessage) message).exception);
				}
				else if (message.getClass() != responseData.responseClass)
				{
					responseData.future.completeExceptionally(new InvalidClassException("Response with invalid type: expected " + responseData.responseClass.getSimpleName() + ", got:" + message));
				}
				else
				{
					responseData.future.complete(trackableMessage);
				}
			}
			else if (this.cancelledFutures.remove(trackableMessage.futureId))
			{
				handled = true;
			}
		}
		
		if (!handled && ModInfo.IS_DEV_BUILD)
		{
			LOGGER.warn("Unhandled message: {}", message);
		}
	}
	
	public abstract <T extends NetworkMessage> void registerHandler(Class<T> handlerClass, Consumer<T> handlerImplementation);
	
	protected final <T extends NetworkMessage> void registerHandler(NetworkEventSource instance, Class<T> handlerClass, Consumer<T> handlerImplementation)
	{
		if (!InternalEvent.class.isAssignableFrom(handlerClass))
		{
			MessageRegistry.INSTANCE.getMessageId(handlerClass);
		}

		//noinspection unchecked
		this.handlers.computeIfAbsent(handlerClass, missingHandlerClass -> new ConcurrentHashMap<>())
				.computeIfAbsent(instance, _instance -> ConcurrentHashMap.newKeySet())
				.add((Consumer<NetworkMessage>) handlerImplementation);
	}
	
	protected void removeAllHandlers(NetworkEventSource childInstance)
	{
		for (ConcurrentMap<NetworkEventSource, Set<Consumer<NetworkMessage>>> handlerMap : this.handlers.values())
		{
			handlerMap.remove(childInstance);
		}
	}
	
	
	protected <TResponse extends TrackableMessage> CompletableFuture<TResponse> createRequest(TrackableMessage msg, Class<TResponse> responseClass)
	{
		CompletableFuture<TResponse> responseFuture = new CompletableFuture<>();
		responseFuture.whenComplete((response, throwable) ->
		{
			if (throwable instanceof CancellationException)
			{
				this.cancelledFutures.add(msg.futureId);
				msg.sendResponse(new CancelMessage());
			}
			
			if (!(throwable instanceof SessionClosedException))
			{
				this.pendingFutures.remove(msg.futureId);
			}
		});
		
		this.pendingFutures.put(msg.futureId, new FutureResponseData(responseClass, responseFuture));
		
		return responseFuture;
	}
	
	protected final void completeAllFuturesExceptionally(Throwable cause)
	{
		for (FutureResponseData responseData : this.pendingFutures.values())
		{
			responseData.future.completeExceptionally(cause);
		}
	}
	
	public void close()
	{
		this.handlers.clear();
		this.completeAllFuturesExceptionally(new SessionClosedException(this.getClass().getSimpleName() + " is closed."));
	}
	
	private static class FutureResponseData
	{
		public final Class<? extends TrackableMessage> responseClass;
		public final CompletableFuture<TrackableMessage> future;
		
		private <T extends TrackableMessage> FutureResponseData(Class<T> responseClass, CompletableFuture<T> future)
		{
			this.responseClass = responseClass;
			//noinspection unchecked
			this.future = (CompletableFuture<TrackableMessage>) future;
		}
		
	}
	
}