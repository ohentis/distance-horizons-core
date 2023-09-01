package com.seibel.distanthorizons.core.network.messages.base;

import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;
import com.seibel.distanthorizons.coreapi.ModInfo;
import io.netty.buffer.ByteBuf;

public class HelloMessage extends NetworkMessage
{
    public int version = ModInfo.PROTOCOL_VERSION;
	
	
	
    @Override
    public void encode(ByteBuf out) { out.writeInt(this.version); }
	
    @Override
    public void decode(ByteBuf in) { this.version = in.readInt(); }
	
}
