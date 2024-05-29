package com.seibel.distanthorizons.core.network.messages.plugin.base;

import com.seibel.distanthorizons.core.network.plugin.PluginChannelMessage;
import io.netty.buffer.ByteBuf;

/**
 * Serves as a trigger for the server to send some useful data to the client.
 * Not integral to establishing a session.
 */
public class ClientHelloMessage extends PluginChannelMessage
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