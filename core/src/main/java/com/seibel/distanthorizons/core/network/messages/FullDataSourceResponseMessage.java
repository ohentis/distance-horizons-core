package com.seibel.distanthorizons.core.network.messages;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.HighDetailIncompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataMetaFile;
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
	private IFullDataSource fullDataSource;
	private DhServerLevel level;
	
	private ByteBufInputStream inputStream;
	
	public FullDataSourceResponseMessage() {}
	public FullDataSourceResponseMessage(IFullDataSource fullDataSource, DhServerLevel level)
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
			
			out.writeInt(outputStream.size());
			out.writeBytes(outputStream.toByteArray());
		}
	}
	
	@Override
	public void decode0(ByteBuf in)
	{
		inputStream = new ByteBufInputStream(in.readBytes(in.readInt()));
	}
	
	public IFullDataSource getFullDataSource(FullDataMetaFile metaFile, DhSectionPos pos, IDhLevel level) throws IOException, InterruptedException
	{
		IFullDataSource fullDataSource = HighDetailIncompleteFullDataSource.createEmpty(pos);
		fullDataSource.populateFromStream(metaFile, new DhDataInputStream(inputStream), level);
		inputStream.close();
		return fullDataSource;
	}
}
