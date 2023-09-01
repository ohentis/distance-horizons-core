package com.seibel.distanthorizons.core.network.messages.base;

import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;
import io.netty.buffer.ByteBuf;

/**
 * This is not a "real" message, and only used to indicate a disconnection.
 * To send a "disconnect reason" message, use {@link CloseReasonMessage}.
 */
public class CloseEvent extends NetworkMessage
{
    @Override
    public void encode(ByteBuf out) { throw new UnsupportedOperationException("CloseMessage is not a real message, and must not be sent."); }

    @Override
    public void decode(ByteBuf in) { throw new UnsupportedOperationException("CloseMessage is not a real message, and must not be received."); }
	
}

