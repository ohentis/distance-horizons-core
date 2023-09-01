package com.seibel.distanthorizons.core.network.messages.fullData.updates;

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class FullDataPartialUpdateMessage extends FutureTrackableNetworkMessage
{
	private ChunkSizedFullDataAccessor fullDataAccessor;
	private DhServerLevel level;
	
	private int levelHashCode;
	private DhChunkPos chunkPos;
	private ByteBuf dataBuffer;
	
	public FullDataPartialUpdateMessage() {}
	public FullDataPartialUpdateMessage(ChunkSizedFullDataAccessor fullDataAccessor, DhServerLevel level)
	{
		this.fullDataAccessor = fullDataAccessor;
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
			fullDataAccessor.writeToStream(dhOutputStream, level);
			dhOutputStream.flush();
			
			out.writeInt(levelHashCode);
			
			out.writeInt(fullDataAccessor.pos.x);
			out.writeInt(fullDataAccessor.pos.z);
			
			out.writeInt(outputStream.size());
			out.writeBytes(outputStream.toByteArray());
		}
	}
	
	@Override
	public void decode0(ByteBuf in)
	{
		levelHashCode = in.readInt();
		
		chunkPos = new DhChunkPos(in.readInt(), in.readInt());
		
		this.dataBuffer = in.readBytes(in.readInt());
	}
	
	@Nullable
	public ChunkSizedFullDataAccessor getFullDataSource(IDhLevel level) throws IOException, InterruptedException
	{
		// TODO Multiverse support
		if (levelHashCode != level.getLevelWrapper().getDimensionType().getDimensionName().hashCode())
			return null;
		
		try (ByteBufInputStream inputStream = new ByteBufInputStream(dataBuffer))
		{
			ChunkSizedFullDataAccessor result = new ChunkSizedFullDataAccessor(chunkPos);
			result.populateFromStream(new DhDataInputStream(inputStream), level);
			return result;
		}
		finally
		{
			dataBuffer.release();
		}
	}
}
