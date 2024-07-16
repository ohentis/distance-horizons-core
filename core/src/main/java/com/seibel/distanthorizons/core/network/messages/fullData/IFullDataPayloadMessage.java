package com.seibel.distanthorizons.core.network.messages.fullData;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;


public interface IFullDataPayloadMessage<T extends IFullDataPayloadMessage<T>> extends Closeable
{
	AtomicInteger lastBufferId = new AtomicInteger();
	
	int getDtoBufferId();
	void setDtoBufferId(int bufferId);
	
	ByteBuf getDtoBuffer();
	void setDtoBuffer(ByteBuf buffer);
	
	
	default void createCompressedDtoBuffer(FullDataSourceV2 fullDataSource)
	{
		Objects.requireNonNull(fullDataSource);
		
		int bufferId = lastBufferId.getAndIncrement();
		this.setDtoBufferId(bufferId);
		
		try
		{
			EDhApiDataCompressionMode compressionMode = Config.Client.Advanced.LodBuilding.dataCompression.get();
			FullDataSourceV2DTO dataSourceDto = FullDataSourceV2DTO.CreateFromDataSource(fullDataSource, compressionMode);
			
			ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer();
			dataSourceDto.encode(buffer);
			this.setDtoBuffer(buffer);
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	default void splitIntoChunks(int chunkSize, Consumer<FullDataChunkMessage> chunkMessageConsumer)
	{
		int bufferId = this.getDtoBufferId();
		ByteBuf dtoBuffer = this.getDtoBuffer();
		
		for (int chunkNum = 0; ; chunkNum++)
		{
			int offset = chunkNum * chunkSize;

			int actualChunkSize = Math.min(dtoBuffer.writerIndex() - offset, chunkSize);
			if (actualChunkSize <= 0)
			{
				break;
			}
			
			FullDataChunkMessage chunk = new FullDataChunkMessage(bufferId, chunkNum == 0, dtoBuffer.slice(offset, actualChunkSize));
			chunkMessageConsumer.accept(chunk);
		}
	}

	default T retain()
	{
		this.getDtoBuffer().retain();
		//noinspection unchecked
		return (T)this;
	}

	default void close()
	{
		this.getDtoBuffer().release();
	}
	
}
