package com.seibel.distanthorizons.core.network.netty;

import com.seibel.distanthorizons.core.network.NetworkEventSource;
import com.seibel.distanthorizons.core.network.messages.netty.base.NettyCloseEvent;
import com.seibel.distanthorizons.core.network.messages.netty.NettyMessageRegistry;
import com.seibel.distanthorizons.core.network.messages.netty.base.CancelMessage;
import com.seibel.distanthorizons.core.network.messages.netty.base.ExceptionMessage;
import io.netty.channel.ChannelException;

import java.io.InvalidClassException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public abstract class NettyEventSource extends NetworkEventSource<NettyMessage>
{
	private final ConcurrentMap<INettyConnection, ConcurrentMap<Long, FutureResponseData>> pendingFutures = new ConcurrentHashMap<>();
	
	public NettyEventSource()
	{
		super(NettyMessageRegistry.INSTANCE);
	}
	
	
	@Override
	public <T extends NettyMessage> void registerHandler(Class<T> handlerClass, Consumer<T> handlerImplementation)
	{
		if (handlerClass != NettyCloseEvent.class)
		{
			// Will throw if the handler class is not found
			this.messageRegistry.getMessageId(handlerClass);
		}
		super.registerHandler(handlerClass, handlerImplementation);
	}
	
	@Override
	protected boolean tryHandleMessage(NettyMessage message)
	{
		if (message instanceof TrackableNettyMessage)
		{
			TrackableNettyMessage trackableMessage = (TrackableNettyMessage) message;
			ConcurrentMap<Long, FutureResponseData> subMap = this.pendingFutures.get(message.getConnection());
			if (subMap != null)
			{
				FutureResponseData responseData = subMap.get(trackableMessage.futureId);
				if (responseData != null)
				{
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
					
					return true;
				}
			}
		}
		
		// Still return true if message should be silent when unhandled
		return !message.warnWhenUnhandled();
	}
	
	protected void addNewConnection(INettyConnection connection)
	{
		this.pendingFutures.put(connection, new ConcurrentHashMap<>());
	}
	
	public <TResponse extends TrackableNettyMessage> CompletableFuture<TResponse> createRequest(INettyConnection connection, TrackableNettyMessage msg, Class<TResponse> responseClass)
	{
		msg.setConnection(connection);
		
		CompletableFuture<TResponse> responseFuture = new CompletableFuture<>();
		responseFuture.whenComplete((response, throwable) ->
		{
			if (!(throwable instanceof ChannelException))
			{
				ConcurrentMap<Long, FutureResponseData> subMap = this.pendingFutures.get(connection);
				if (subMap != null)
				{
					subMap.remove(msg.futureId);
				}
			}
			
			if (throwable instanceof CancellationException)
			{
				msg.sendResponse(new CancelMessage());
			}
		});
		
		ConcurrentMap<Long, FutureResponseData> subMap = this.pendingFutures.get(connection);
		if (subMap == null)
		{
			// Was deleted before adding
			responseFuture.completeExceptionally(connection.getCloseReason());
			return responseFuture;
		}
		subMap.put(msg.futureId, new FutureResponseData(responseClass, responseFuture));
		if (!this.pendingFutures.containsKey(connection))
		{
			// Was deleted while adding
			// Note: removal from subMap will happen in whenComplete above
			responseFuture.completeExceptionally(connection.getCloseReason());
			return responseFuture;
		}
		// If passed until here, cancelling is up to the cleaning side
		
		return responseFuture;
	}
	
	protected final void completeAllFuturesExceptionally(INettyConnection connection, Throwable cause)
	{
		ConcurrentMap<Long, FutureResponseData> map = this.pendingFutures.remove(connection);
		if (map == null)
		{
			return;
		}
		
		for (FutureResponseData responseData : map.values())
		{
			responseData.future.completeExceptionally(cause);
		}
	}
	
	protected final void completeAllFuturesExceptionally(Throwable cause)
	{
		for (INettyConnection connection : this.pendingFutures.keySet())
		{
			this.completeAllFuturesExceptionally(connection, cause);
		}
	}
	
	@Override
	public void close()
	{
		super.close();
		this.completeAllFuturesExceptionally(new ChannelException(this.getClass().getSimpleName() + " is closed."));
	}
	
	
	private static class FutureResponseData
	{
		public final Class<? extends TrackableNettyMessage> responseClass;
		public final CompletableFuture<TrackableNettyMessage> future;
		
		private <T extends TrackableNettyMessage> FutureResponseData(Class<T> responseClass, CompletableFuture<T> future)
		{
			this.responseClass = responseClass;
			//noinspection unchecked
			this.future = (CompletableFuture<TrackableNettyMessage>) future;
		}
		
	}
	
}
