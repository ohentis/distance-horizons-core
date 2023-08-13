package com.seibel.distanthorizons.core.network.messages;

import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

public class GenTaskPriorityRequestMessage extends FutureTrackableNetworkMessage
{
	public List<DhSectionPos> posList = new ArrayList<>();
	
	public GenTaskPriorityRequestMessage() { }
	public GenTaskPriorityRequestMessage(List<DhSectionPos> posList)
	{
		this.posList = posList;
	}
	
	@Override
	protected void encode0(ByteBuf out)
	{
		encodeCollection(out, posList);
	}
	
	@Override
	protected void decode0(ByteBuf in)
	{
		decodeCollection(in, posList, DhSectionPos::zero);
	}
}
