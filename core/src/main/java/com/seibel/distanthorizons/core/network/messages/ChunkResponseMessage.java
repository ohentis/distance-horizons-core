package com.seibel.distanthorizons.core.network.messages;

import com.seibel.distanthorizons.core.network.future.IFutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import io.netty.buffer.ByteBuf;

public class ChunkResponseMessage implements IFutureTrackableNetworkMessage<DhSectionPos>
{
	public DhSectionPos dhSectionPos;

	public ChunkResponseMessage() {}

	public ChunkResponseMessage(DhSectionPos dhSectionPos) {
		this.dhSectionPos = dhSectionPos;
	}
	
	@Override public DhSectionPos getRequestKey() { return dhSectionPos; }
	
	@Override
	public void encode(ByteBuf out)
	{
		dhSectionPos.encode(out);
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		dhSectionPos = INetworkObject.decode(new DhSectionPos((byte)0, 0, 0), in);
	}
}
