package com.seibel.distanthorizons.core.network.messages;

import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import io.netty.buffer.ByteBuf;

public class ChunkResponseMessage extends FutureTrackableNetworkMessage
{
	public ChunkResponseMessage() {}
	
	@Override
	public void encode0(ByteBuf out)
	{
	}
	
	@Override
	public void decode0(ByteBuf in)
	{
	}
}
