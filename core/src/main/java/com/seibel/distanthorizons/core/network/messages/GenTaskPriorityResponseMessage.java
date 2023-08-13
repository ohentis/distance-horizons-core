package com.seibel.distanthorizons.core.network.messages;

import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import io.netty.buffer.ByteBuf;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GenTaskPriorityResponseMessage extends FutureTrackableNetworkMessage
{
	public Map<DhSectionPos, Integer> posList = new HashMap<>();
	
	public GenTaskPriorityResponseMessage() { }
	public GenTaskPriorityResponseMessage(Map<DhSectionPos, Integer> posList)
	{
		this.posList = posList;
	}
	
	@Override
	protected void encode0(ByteBuf out)
	{
		encodeCollection(out, posList.entrySet());
	}
	
	@Override
	protected void decode0(ByteBuf in)
	{
		decodeMap(in, posList, DhSectionPos::zero, () -> 0);
	}
}
