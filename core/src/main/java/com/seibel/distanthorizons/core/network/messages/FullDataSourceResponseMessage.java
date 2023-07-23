package com.seibel.distanthorizons.core.network.messages;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import io.netty.buffer.ByteBuf;

import java.io.IOException;

public class FullDataSourceResponseMessage extends FutureTrackableNetworkMessage
{
	public IFullDataSource fullDataSource;
	public DhServerLevel level;
	
	public FullDataSourceResponseMessage() {}
	public FullDataSourceResponseMessage(IFullDataSource fullDataSource, DhServerLevel level)
	{
		this.fullDataSource = fullDataSource;
		this.level = level;
	}
	
	@Override
	public void encode0(ByteBuf out) throws IOException
	{
		//fullDataSource.writeToStream(new DhDataOutputStream(new ByteBufOutputStream(out)), level);
	}
	
	@Override
	public void decode0(ByteBuf in)
	{
		//DhSectionPos sectionPos = INetworkObject.decode(new DhSectionPos((byte) 0, (byte) 0, (byte) 0), in);
		//fullDataSource = HighDetailIncompleteFullDataSource.createEmpty(sectionPos);
	}
}
