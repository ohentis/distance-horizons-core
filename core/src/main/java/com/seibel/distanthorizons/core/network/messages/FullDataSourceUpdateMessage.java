package com.seibel.distanthorizons.core.network.messages;

import com.seibel.distanthorizons.core.dataObjects.fullData.loader.AbstractFullDataSourceLoader;
import com.seibel.distanthorizons.core.dataObjects.fullData.loader.CompleteFullDataSourceLoader;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class FullDataSourceUpdateMessage extends FutureTrackableNetworkMessage
{
	private CompleteFullDataSource fullDataSource;
	private DhServerLevel level;
	
	private int levelHashCode;
	private DhSectionPos sectionPos;
	private CompleteFullDataSourceLoader fullDataSourceLoader;
	private ByteBuf dataBuffer;
	
	public FullDataSourceUpdateMessage() {}
	public FullDataSourceUpdateMessage(CompleteFullDataSource fullDataSource, DhServerLevel level)
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
			fullDataSource.getSectionPos().encode(out);
			out.writeByte(fullDataSource.getBinaryDataFormatVersion());
			out.writeInt(outputStream.size());
			out.writeBytes(outputStream.toByteArray());
		}
	}
	
	@Override
	public void decode0(ByteBuf in)
	{
		levelHashCode = in.readInt();
		sectionPos = INetworkObject.decodeStatic(DhSectionPos.zero(), in);
		byte dataVersion = in.readByte();
		this.fullDataSourceLoader = (CompleteFullDataSourceLoader) AbstractFullDataSourceLoader.getLoader(CompleteFullDataSource.TYPE_ID, dataVersion);
		this.dataBuffer = in.readBytes(in.readInt());
	}
	
	@Nullable
	public CompleteFullDataSource getFullDataSource(IDhLevel level) throws IOException, InterruptedException
	{
		// TODO Multiverse support
		if (levelHashCode != level.getLevelWrapper().getDimensionType().getDimensionName().hashCode())
			return null;
		
		try (ByteBufInputStream inputStream = new ByteBufInputStream(dataBuffer))
		{
			return fullDataSourceLoader.loadData(sectionPos, new DhDataInputStream(inputStream), level);
		}
		finally
		{
			dataBuffer.release();
		}
	}
}
