package com.seibel.distanthorizons.core.network.messages.fullData;

import com.google.common.base.MoreObjects;
import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.network.INetworkObject;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;


public class FullDataPayload implements INetworkObject, Closeable
{
	private static final AtomicInteger lastBufferId = new AtomicInteger();
	
	public int dtoBufferId;
	public ByteBuf dtoBuffer;
	
	
	public FullDataPayload() { }
	public FullDataPayload(@NotNull FullDataSourceV2 fullDataSource)
	{
		Objects.requireNonNull(fullDataSource);
		
		this.dtoBufferId = lastBufferId.getAndIncrement();
		
		try
		{
			EDhApiDataCompressionMode compressionMode = Config.Client.Advanced.LodBuilding.dataCompression.get();
			FullDataSourceV2DTO dataSourceDto = FullDataSourceV2DTO.CreateFromDataSource(fullDataSource, compressionMode);
			
			this.dtoBuffer = ByteBufAllocator.DEFAULT.buffer();
			dataSourceDto.encode(this.dtoBuffer);
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	
	public void acceptInChunkMessages(int chunkSize, Consumer<FullDataChunkMessage> chunkMessageConsumer)
	{
		for (int chunkNum = 0; ; chunkNum++)
		{
			int offset = chunkNum * chunkSize;
			
			int actualChunkSize = Math.min(this.dtoBuffer.writerIndex() - offset, chunkSize);
			if (actualChunkSize <= 0)
			{
				break;
			}
			
			FullDataChunkMessage chunk = new FullDataChunkMessage(this.dtoBufferId, chunkNum == 0, this.dtoBuffer.slice(offset, actualChunkSize));
			chunkMessageConsumer.accept(chunk);
		}
	}
	

	public FullDataPayload retain()
	{
		this.dtoBuffer.retain();
		return this;
	}
	
	@Override public void close()
	{
		this.dtoBuffer.release();
	}
	
	
	@Override
	public void encode(ByteBuf out)
	{
		out.writeInt(this.dtoBufferId);
		this.dtoBuffer.release();
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		this.dtoBufferId = in.readInt();
	}
	
	
	@Override
	public String toString()
	{
		return MoreObjects.toStringHelper(this)
				.add("dtoBufferId", this.dtoBufferId)
				.add("dtoBuffer", this.dtoBuffer)
				.toString();
	}
	
}
