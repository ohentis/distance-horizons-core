/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2023 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.network.messages.fullData.updates;

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class FullDataPartialUpdateMessage extends NetworkMessage
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
	
	public void encode(ByteBuf out)
	{
		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream())
		{
			DhDataOutputStream dhOutputStream = new DhDataOutputStream(outputStream);
			fullDataAccessor.writeToStream(dhOutputStream, level);
			dhOutputStream.flush();
			
			out.writeInt(levelHashCode);
			
			out.writeInt(fullDataAccessor.chunkPos.x);
			out.writeInt(fullDataAccessor.chunkPos.z);
			
			out.writeInt(outputStream.size());
			out.writeBytes(outputStream.toByteArray());
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	public void decode(ByteBuf in)
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
	
	@Override public String toString()
	{
		return super.toString(
				"levelHashCode=" + levelHashCode +
				", chunkPos=" + chunkPos +
				", dataBuffer=" + dataBuffer
		);
	}
	
}
