package com.seibel.distanthorizons.core.network.messages;

import com.seibel.distanthorizons.core.network.future.IFutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import io.netty.buffer.ByteBuf;

public class ChunkResponseMessage implements IFutureTrackableNetworkMessage<DhSectionPos>
{
	public DhSectionPos dhSectionPos;
	
	@Override public DhSectionPos getRequestKey() { return dhSectionPos; }
	
	@Override public void encode(ByteBuf out)
	{
	
	}
	
	@Override public void decode(ByteBuf in)
	{
	
	}
}
