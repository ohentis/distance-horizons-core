package com.seibel.distanthorizons.core.network.future;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.seibel.distanthorizons.core.network.ChildNetworkEventSource;
import com.seibel.distanthorizons.core.network.NetworkEventSource;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundInvoker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class NetworkRequestTracker
		<TResponse extends IFutureTrackableNetworkMessage<TKey>, TKey extends Comparable<TKey>>
	implements AutoCloseable
{
	private final ChildNetworkEventSource<?> eventSource;
	
	private final Set<Channel> knownChannels = new HashSet<>();
	private final Table<Channel, TKey, CompletableFuture<TResponse>> pendingFutures = HashBasedTable.create();
	
	public NetworkRequestTracker(
			NetworkEventSource eventSource,
			Class<TResponse> responseClass)
	{
		this.eventSource = new ChildNetworkEventSource<>(eventSource);
		registerNetworkHandlers(responseClass);
	}
	
	private void registerNetworkHandlers(Class<TResponse> responseClass)
	{
		this.eventSource.registerHandler(responseClass, (msg, ctx) -> {
			CompletableFuture<TResponse> future = pendingFutures.remove(ctx.channel(), msg.getRequestKey());
			if (future != null) {
				future.complete(msg);
			}
		});
	}
	
	public CompletableFuture<TResponse> sendRequest(Channel channel, IFutureTrackableNetworkMessage<TKey> msg)
	{
		if (knownChannels.add(channel))
			channel.closeFuture().addListener(closeFuture -> completeAllExceptionally(channel, closeFuture.cause()));
		
		CompletableFuture<TResponse> responseFuture = new CompletableFuture<>();
		pendingFutures.put(channel, msg.getRequestKey(), responseFuture);
		
		channel.writeAndFlush(msg).addListener(writeFuture -> {
			if (writeFuture.cause() != null) {
				responseFuture.completeExceptionally(writeFuture.cause());
			}
		});
		return responseFuture;
	}
	
	private void completeAllExceptionally(Channel channel, Throwable cause) {
		for (CompletableFuture<TResponse> responseFuture : pendingFutures.row(channel).values()) {
			responseFuture.completeExceptionally(cause);
		};
		pendingFutures.row(channel).clear();
	}
	
	@Override public void close()
	{
		this.eventSource.close();
		for (Channel channel : pendingFutures.rowKeySet())
			this.completeAllExceptionally(channel, new Exception(this.getClass().getSimpleName()+" is closed."));
	}
}
