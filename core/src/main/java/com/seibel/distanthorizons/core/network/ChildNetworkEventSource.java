package com.seibel.distanthorizons.core.network;

import com.seibel.distanthorizons.core.network.protocol.INetworkMessage;
import io.netty.channel.ChannelHandlerContext;

import java.util.function.BiConsumer;

/** Provides a way to register network message handlers which are expected to be removed later. */
public final class ChildNetworkEventSource<TParent extends NetworkEventSource> extends NetworkEventSource
{
	private final TParent parent;
	private boolean isClosed = false;
	
	public ChildNetworkEventSource(TParent parent)
	{
		this.parent = parent;
	}
	public ChildNetworkEventSource(ChildNetworkEventSource<TParent> child)
	{
		this(child.parent);
	}
	
	@Override public <T extends INetworkMessage> void registerHandler(Class<T> handlerClass, BiConsumer<T, ChannelHandlerContext> handlerImplementation)
	{
		if (isClosed) return;
		
		if (!this.hasHandler(handlerClass))
		{
			parent.registerHandler(handlerClass, this::handleMessage);
		}
		
		super.registerHandler(handlerClass, handlerImplementation);
	}
	
	@Override public void close()
	{
		isClosed = true;
		for (Class<? extends INetworkMessage> handlerClass : this.handlers.keySet())
		{
			parent.removeHandler(handlerClass, this::handleMessage);
		}
	}
}
