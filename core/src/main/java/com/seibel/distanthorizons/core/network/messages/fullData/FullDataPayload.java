package com.seibel.distanthorizons.core.network.messages.fullData;

import com.google.common.base.MoreObjects;
import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.network.INetworkObject;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.TimerUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * @see FullDataSplitMessage
 */
public class FullDataPayload implements INetworkObject
{
	private static final AtomicInteger lastBufferId = new AtomicInteger();
	
	// Reference counting is unreliable here for some reason so this is a "fix"
	private static final Timer bufferCleanupTimer = TimerUtil.CreateTimer("FullDataBufferCleanupTimer");
	
	public int dtoBufferId;
	public ByteBuf dtoBuffer;
	
	
	
	//==============//
	// constructors //
	//==============//
	
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
			
			bufferCleanupTimer.schedule(new TimerTask()
			{
				@Override
				public void run()
				{
					FullDataPayload.this.dtoBuffer.release();
				}
			}, 5000L);
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	
	
	//===============//
	// serialization //
	//===============//
	
	@Override
	public void encode(ByteBuf out)
	{
		out.writeInt(this.dtoBufferId);
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		this.dtoBufferId = in.readInt();
	}
	
	/**
	 * Used to send {@link FullDataPayload}'s since the data they contain may be larger
	 * than what a single packet could contain.
	 *
	 * @param payloadChunkSizeInBytes how many bytes can be sent in a single message 
	 */
	public void splitAndSend(int payloadChunkSizeInBytes, Consumer<FullDataSplitMessage> sendMessageConsumer)
	{
		// chunk in this context means chunk of data, not a MC chunk
		for (int payloadChunkNum = 0; ; payloadChunkNum++)
		{
			int offset = payloadChunkNum * payloadChunkSizeInBytes;
			
			int actualChunkSize = Math.min(this.dtoBuffer.writerIndex() - offset, payloadChunkSizeInBytes);
			if (actualChunkSize <= 0)
			{
				break;
			}
			
			FullDataSplitMessage chunk = new FullDataSplitMessage(this.dtoBufferId, payloadChunkNum == 0, this.dtoBuffer.slice(offset, actualChunkSize));
			sendMessageConsumer.accept(chunk);
		}
	}
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public String toString()
	{
		return MoreObjects.toStringHelper(this)
				.add("dtoBufferId", this.dtoBufferId)
				.add("dtoBuffer", this.dtoBuffer)
				.toString();
	}
	
}
