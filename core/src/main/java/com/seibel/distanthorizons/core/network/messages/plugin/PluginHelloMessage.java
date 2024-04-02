package com.seibel.distanthorizons.core.network.messages.plugin;

import com.seibel.distanthorizons.core.network.plugin.PluginChannelMessage;
import io.netty.buffer.ByteBuf;

/** Serves as a trigger for the server to send the first world change. */
public class PluginHelloMessage extends PluginChannelMessage
{
	@Override
	public void encode(ByteBuf out)
	{
		
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		
	}
	
}
