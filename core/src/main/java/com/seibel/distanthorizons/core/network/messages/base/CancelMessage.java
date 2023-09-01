package com.seibel.distanthorizons.core.network.messages.base;

import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import io.netty.buffer.ByteBuf;

public class CancelMessage extends FutureTrackableNetworkMessage
{
	public CancelMessage() { }
	
	@Override
	public void encode0(ByteBuf out)
	{
	}
	
	@Override
	public void decode0(ByteBuf in)
	{
	}
}
