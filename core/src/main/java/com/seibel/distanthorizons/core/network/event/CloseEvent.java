package com.seibel.distanthorizons.core.network.event;

import com.seibel.distanthorizons.core.network.messages.NetworkMessage;
import io.netty.buffer.ByteBuf;

/**
 * This is not a "real" message, and only used to indicate a disconnection.
 */
public class CloseEvent extends NetworkMessage
{
	@Override
	public void encode(ByteBuf out) { throw new UnsupportedOperationException(this.getClass().getSimpleName() + " is not a real message, and cannot be sent."); }
	@Override
	public void decode(ByteBuf in) { throw new UnsupportedOperationException(this.getClass().getSimpleName() + " is not a real message, and cannot be received."); }
	
}