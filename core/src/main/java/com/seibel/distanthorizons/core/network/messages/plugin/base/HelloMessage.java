package com.seibel.distanthorizons.core.network.messages.plugin.base;

import com.seibel.distanthorizons.core.network.plugin.PluginChannelMessage;
import com.seibel.distanthorizons.coreapi.ModInfo;
import io.netty.buffer.ByteBuf;

/** Serves as a trigger for the server to send the first world change. */
public class HelloMessage extends PluginChannelMessage
{
	public short version = ModInfo.PROTOCOL_VERSION;
	
	@Override
	public void encode(ByteBuf out)
	{
		out.writeShort(this.version);
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		this.version = in.readShort();
	}
	
}