package com.seibel.distanthorizons.core.network.messages;

import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import io.netty.buffer.ByteBuf;

public class CloseReasonMessage extends NetworkMessage
{
	public String reason;
	
	
	
	public CloseReasonMessage() { }
	public CloseReasonMessage(String reason) { this.reason = reason; }
	
	@Override
	public void encode(ByteBuf out) { INetworkObject.encodeString(this.reason, out); }
	
	@Override
	public void decode(ByteBuf in) { this.reason = INetworkObject.decodeString(in); }
	
}
