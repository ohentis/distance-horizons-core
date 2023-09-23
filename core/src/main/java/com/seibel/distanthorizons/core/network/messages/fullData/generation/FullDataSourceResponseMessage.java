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
	
	private CompleteFullDataSourceLoader fullDataSourceLoader;
	public CompleteFullDataSourceLoader getFullDataSourceLoader() { return fullDataSourceLoader; }
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
		this.dataBuffer = in.readBytes(in.readInt());
	}
	
	public CompleteFullDataSource getFullDataSource(DhSectionPos pos, IDhLevel level) throws IOException, InterruptedException
	{
		try (ByteBufInputStream inputStream = new ByteBufInputStream(dataBuffer))
		{
			return fullDataSourceLoader.loadData(pos, new DhDataInputStream(inputStream), level);
		}
		finally
		{
			dataBuffer.release();
		}
	}
	
	@Override public String toString()
	{
		return super.toString("dataBuffer=" + dataBuffer);
	}
	
}
