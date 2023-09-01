package com.seibel.distanthorizons.core.network.messages.fullData.updates;

import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import io.netty.buffer.ByteBuf;

import java.util.HashSet;
import java.util.Set;

public class FullDataChangeSummaryResponseMessage extends FutureTrackableNetworkMessage
{
	public final Set<DhSectionPos> changedPosList;

	public FullDataChangeSummaryResponseMessage()
	{
		this.changedPosList = new HashSet<>();
	}

	public FullDataChangeSummaryResponseMessage(Set<DhSectionPos> changedPosList)
	{
		this.changedPosList = changedPosList;
	}
	
    @Override
    public void encode0(ByteBuf out)
	{
		encodeCollection(out, changedPosList);
    }

    @Override
    public void decode0(ByteBuf in)
	{
		decodeCollection(in, changedPosList, DhSectionPos::zero);
    }
}
