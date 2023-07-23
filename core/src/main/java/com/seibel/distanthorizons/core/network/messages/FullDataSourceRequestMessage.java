package com.seibel.distanthorizons.core.network.messages;

import com.google.common.collect.MapMaker;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import io.netty.buffer.ByteBuf;

import java.util.concurrent.ConcurrentMap;

public class FullDataSourceRequestMessage extends FutureTrackableNetworkMessage
{
	public DhSectionPos dhSectionPos;

	public FullDataSourceRequestMessage() {}

	public FullDataSourceRequestMessage(DhSectionPos dhSectionPos) {
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
		dhSectionPos = INetworkObject.decode(new DhSectionPos((byte)0, 0, 0), in);
    }
}
