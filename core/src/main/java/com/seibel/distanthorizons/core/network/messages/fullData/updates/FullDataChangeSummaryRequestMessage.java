package com.seibel.distanthorizons.core.network.messages.fullData.updates;

import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import io.netty.buffer.ByteBuf;

import java.util.HashMap;
import java.util.Map;

public class FullDataChangeSummaryRequestMessage extends FutureTrackableNetworkMessage
{
	public Map<DhSectionPos, Integer> checksums = new HashMap<>();
	public int levelHashCode;

	public FullDataChangeSummaryRequestMessage() { }

	public FullDataChangeSummaryRequestMessage(ILevelWrapper levelWrapper, Map<DhSectionPos, Integer> checksums)
	{
		// TODO Multiverse support
		this.levelHashCode = levelWrapper.getDimensionType().getDimensionName().hashCode();
		this.checksums = checksums;
	}
	
    @Override
    public void encode0(ByteBuf out)
	{
		// TODO Multiverse support
		out.writeInt(levelHashCode);
		encodeCollection(out, checksums.entrySet());
    }

    @Override
    public void decode0(ByteBuf in)
	{
		levelHashCode = in.readInt();
		decodeMap(in, checksums, DhSectionPos::zero, () -> 0);
    }
	
	public boolean isLevelValid(ILevelWrapper levelWrapper)
	{
		return levelWrapper.getDimensionType().getDimensionName().hashCode() == levelHashCode;
	}
	
}
