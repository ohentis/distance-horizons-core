package com.seibel.distanthorizons.core.network.messages.fullData;

import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;

import java.util.ArrayList;
import java.util.List;

public interface IFullDataPayloadMessage
{
	FullDataSourceV2DTO getDataSourceDto();
	
	default List<ByteBuf> getDataSourceDtoChunks(int chunkSize)
	{
		FullDataSourceV2DTO dto = this.getDataSourceDto();
		int chunkCount = dto.estimatedEncodedSize() / chunkSize;
		
		CompositeByteBuf composite = ByteBufAllocator.DEFAULT.compositeBuffer();
		
		ArrayList<ByteBuf> result = new ArrayList<>();
		for (int i = 0; i < chunkCount; i++)
		{
			ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer();
			result.add(buffer);
			composite.addComponent(buffer);
		}
		
		dto.encode(composite);
		
		composite.release();
		return result;
	}
	
}
