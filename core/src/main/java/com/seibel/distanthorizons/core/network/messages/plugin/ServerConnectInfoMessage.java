package com.seibel.distanthorizons.core.network.messages.plugin;

import com.seibel.distanthorizons.core.network.plugin.PluginChannelMessage;
import io.netty.buffer.ByteBuf;

import javax.annotation.Nullable;

public class ServerConnectInfoMessage extends PluginChannelMessage
{
	@Nullable
	public String ipOverride;
	public int port;
	
	public ServerConnectInfoMessage() { }
	public ServerConnectInfoMessage(@Nullable String ipOverride, int port)
	{
		this.ipOverride = ipOverride;
		this.port = port;
	}
	
	@Override
	public void encode(ByteBuf out)
	{
		if (this.writeOptional(out, this.ipOverride))
		{
			this.writeString(this.ipOverride, out);
		}
		out.writeShort(this.port);
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		this.ipOverride = this.readOptional(in, () -> this.readString(in));
		this.port = in.readShort();
	}
	
}
