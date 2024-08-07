package com.seibel.distanthorizons.core.network.event;

import com.seibel.distanthorizons.core.network.messages.NetworkMessage;
import io.netty.buffer.ByteBuf;

public abstract class InternalEvent extends NetworkMessage
{
	@Override
	public void encode(ByteBuf out)
	{ 
		throw new UnsupportedOperationException(this.getClass().getSimpleName() + " is an internal event, and cannot be sent.");
	}

	@Override
	public void decode(ByteBuf in)
	{
		throw new UnsupportedOperationException(this.getClass().getSimpleName() + " is an internal event, and cannot be received.");
	}
	
}