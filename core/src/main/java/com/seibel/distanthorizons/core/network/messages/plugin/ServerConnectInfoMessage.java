package com.seibel.distanthorizons.core.network.messages.plugin;

import com.seibel.distanthorizons.core.network.plugin.PluginChannelMessage;
import io.netty.buffer.ByteBuf;

public class ServerConnectInfoMessage extends PluginChannelMessage
{
	public String ipAddress;
	public short port;
	
	public ServerConnectInfoMessage() { }
	public ServerConnectInfoMessage(String ipAddress, short port)
	{
		this.ipAddress = ipAddress;
		this.port = port;
	}
	
	@Override
	public void encode(ByteBuf out)
	{
		this.writeString(this.ipAddress, out);
		out.writeShort(this.port);
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		this.ipAddress = this.readString(in);
		this.port = in.readShort();
	}
	
}
