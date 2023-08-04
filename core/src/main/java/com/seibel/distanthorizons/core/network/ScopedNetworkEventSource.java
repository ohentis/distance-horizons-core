package com.seibel.distanthorizons.core.network;

import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;

import java.util.function.Consumer;

/** Provides a way to register network message handlers which are expected to be removed later. */
public final class ScopedNetworkEventSource<TParent extends NetworkEventSource> extends NetworkEventSource
{
	public final TParent parent;
	private boolean isClosed = false;
	
	public ScopedNetworkEventSource(TParent parent)
	{
		this.parent = parent;
	}
	
	@Override
	public <T extends NetworkMessage> void registerHandler(Class<T> handlerClass, Consumer<T> handlerImplementation)
	{
		if (isClosed) return;
		
		if (!this.hasHandler(handlerClass))
		{
			parent.registerHandler(handlerClass, this::handleMessage);
		}
		
		super.registerHandler(handlerClass, handlerImplementation);
	}
	
	@Override
	public void close()
	{
		isClosed = true;
		for (Class<? extends NetworkMessage> handlerClass : this.handlers.keySet())
		{
			parent.removeHandler(handlerClass, this::handleMessage);
		}
	}
}
