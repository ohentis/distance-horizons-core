package com.seibel.distanthorizons.core.network.messages.fullData.generation;

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

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class FullDataSourceResponseMessage extends FutureTrackableNetworkMessage
{
	private CompleteFullDataSource fullDataSource;
	private DhServerLevel level;
	
	private int levelHashCode;
	private CompleteFullDataSourceLoader fullDataSourceLoader;
	private ByteBuf dataBuffer;
	
	public FullDataSourceResponseMessage() {}
	public FullDataSourceResponseMessage(CompleteFullDataSource fullDataSource, DhServerLevel level)
	{
		this.fullDataSource = fullDataSource;
		this.level = level;
		
		// TODO Multiverse support
		this.levelHashCode = level.getLevelWrapper().getDimensionType().getDimensionName().hashCode();
	}
	
	@Override
	public void encode0(ByteBuf out) throws IOException
	{
		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream())
		{
			DhDataOutputStream dhOutputStream = new DhDataOutputStream(outputStream);
			fullDataSource.writeToStream(dhOutputStream, level);
			dhOutputStream.flush();
			
			out.writeInt(levelHashCode);
			out.writeByte(fullDataSource.getBinaryDataFormatVersion());
			out.writeInt(outputStream.size());
			out.writeBytes(outputStream.toByteArray());
		}
	}
	
	@Override
	public void decode0(ByteBuf in)
	{
		levelHashCode = in.readInt();
		byte dataVersion = in.readByte();
		this.fullDataSourceLoader = (CompleteFullDataSourceLoader) AbstractFullDataSourceLoader.getLoader(CompleteFullDataSource.TYPE_ID, dataVersion);
		this.dataBuffer = in.readBytes(in.readInt());
	}
	
	@Nullable
	public CompleteFullDataSource getFullDataSource(DhSectionPos pos, IDhLevel level) throws IOException, InterruptedException
	{
		// TODO Multiverse support
		if (levelHashCode != level.getLevelWrapper().getDimensionType().getDimensionName().hashCode())
			return null;
		
		try (ByteBufInputStream inputStream = new ByteBufInputStream(dataBuffer))
		{
			return fullDataSourceLoader.loadData(pos, new DhDataInputStream(inputStream), level);
		}
		finally
		{
			dataBuffer.release();
		}
	}
}
