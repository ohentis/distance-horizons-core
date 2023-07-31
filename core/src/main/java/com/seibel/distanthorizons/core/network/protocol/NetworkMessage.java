package com.seibel.distanthorizons.core.network.protocol;

import io.netty.channel.ChannelHandlerContext;

public abstract class NetworkMessage implements INetworkObject
{
	private ChannelHandlerContext channelContext = null;
	
	public ChannelHandlerContext getChannelContext()
	{
		return channelContext;
	}
	
	public void setChannelContext(ChannelHandlerContext channelContext)
	{
		if (this.channelContext != null)
			throw new IllegalStateException("Channel context cannot be changed after initial setting.");
		this.channelContext = channelContext;
	}
}

