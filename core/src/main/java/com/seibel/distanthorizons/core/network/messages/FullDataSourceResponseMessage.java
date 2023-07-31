package com.seibel.distanthorizons.core.network.messages;

import com.seibel.distanthorizons.core.dataObjects.fullData.loader.AbstractFullDataSourceLoader;
import com.seibel.distanthorizons.core.dataObjects.fullData.loader.CompleteFullDataSourceLoader;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class FullDataSourceResponseMessage extends FutureTrackableNetworkMessage
{
	private CompleteFullDataSource fullDataSource;
	private DhServerLevel level;
	
	private CompleteFullDataSourceLoader fullDataSourceLoader;
	private ByteBuf dataBuffer;
	
	public FullDataSourceResponseMessage() {}
	public FullDataSourceResponseMessage(CompleteFullDataSource fullDataSource, DhServerLevel level)
	{
		this.fullDataSource = fullDataSource;
		this.level = level;
	}
	
	@Override
	public void encode0(ByteBuf out) throws IOException
	{
		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream())
		{
			DhDataOutputStream dhOutputStream = new DhDataOutputStream(outputStream);
			fullDataSource.writeToStream(dhOutputStream, level);
			dhOutputStream.flush();
			
			out.writeByte(fullDataSource.getBinaryDataFormatVersion());
			out.writeInt(outputStream.size());
			out.writeBytes(outputStream.toByteArray());
		}
	}
	
	@Override
	public void decode0(ByteBuf in)
	{
		byte dataVersion = in.readByte();
		
		this.fullDataSourceLoader = (CompleteFullDataSourceLoader) AbstractFullDataSourceLoader.getLoader(CompleteFullDataSource.TYPE_ID, dataVersion);
		assert this.fullDataSourceLoader != null;
		
		this.dataBuffer = in.readBytes(in.readInt());
	}
	
	public CompleteFullDataSource getFullDataSource(DhSectionPos pos, IDhLevel level) throws IOException, InterruptedException
	{
		try (ByteBufInputStream inputStream = new ByteBufInputStream(dataBuffer))
		{
			return fullDataSourceLoader.loadData(pos, new DhDataInputStream(inputStream), level);
		}
	}
}
