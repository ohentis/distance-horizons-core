package com.seibel.distanthorizons.core.network.messages.fullData.generation;

import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import io.netty.buffer.ByteBuf;

import javax.annotation.Nullable;

public class FullDataSourceRequestMessage extends FutureTrackableNetworkMessage
{
	public DhSectionPos dhSectionPos;

	public FullDataSourceRequestMessage() {}

	public FullDataSourceRequestMessage(DhSectionPos dhSectionPos)
	{
		this.dhSectionPos = dhSectionPos;
	}

    @Override
    public void encode0(ByteBuf out)
	{
		dhSectionPos.encode(out);
    }

    @Override
    public void decode0(ByteBuf in)
	{
		dhSectionPos = INetworkObject.decodeStatic(DhSectionPos.zero(), in);
    }
}
