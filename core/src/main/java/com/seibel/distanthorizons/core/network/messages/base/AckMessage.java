package com.seibel.distanthorizons.core.network.messages.base;

import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.MessageRegistry;
import io.netty.buffer.ByteBuf;

/**
 * Simple empty response message.
 * This message is not sent automatically.
 */
public class AckMessage extends FutureTrackableNetworkMessage
{
	public AckMessage() { }
	
	@Override
	public void encode0(ByteBuf out) { }
	
	@Override
	public void decode0(ByteBuf in) { }
	
}
