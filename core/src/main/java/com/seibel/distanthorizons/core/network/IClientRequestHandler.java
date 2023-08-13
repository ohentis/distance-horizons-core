package com.seibel.distanthorizons.core.network;

import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;

import java.util.concurrent.CompletableFuture;

public interface IClientRequestHandler
{
	/** Indicates whether the client is initialized and not started connecting yet. */
	boolean isInitialState();
	/** Indicates whether the client is closed(-ing) and should not be used. */
	boolean isClosed();
	/** Indicates whether the connection is established and first message is sent. */
	boolean isReady();
	
	/** Sends a new request. */
	<TResponse extends FutureTrackableNetworkMessage> CompletableFuture<TResponse> sendRequest(FutureTrackableNetworkMessage msg);
}

