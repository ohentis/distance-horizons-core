package com.seibel.distanthorizons.core.network.messages;

import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import io.netty.buffer.ByteBuf;

public interface ICloseEvent extends INetworkObject
{
	@Override
	default void encode(ByteBuf out) { throw new UnsupportedOperationException(this.getClass().getSimpleName() + " is not a real message, and cannot be sent."); }
	
	@Override
	default void decode(ByteBuf in) { throw new UnsupportedOperationException(this.getClass().getSimpleName() + " is not a real message, and cannot be received."); }
	
}
