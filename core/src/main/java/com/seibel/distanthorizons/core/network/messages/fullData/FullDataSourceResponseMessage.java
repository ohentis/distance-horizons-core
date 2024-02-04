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

package com.seibel.distanthorizons.core.network.messages.fullData;

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

/**
 * Response message, containing the requested full data source,
 * or nothing if requested in updates-only mode and the data was not updated. <br>
 * Decoded full data source is not cached, since it's intended for a single use.
 */
public class FullDataSourceResponseMessage extends FutureTrackableNetworkMessage
{
	// Transmitted data
	@Nullable
	private ByteBuf dataBuffer;
	
	// Used only when encoding
	@Nullable
	private CompleteFullDataSource fullDataSource;
	private DhServerLevel level;
	
	// Used only when decoding
	private CompleteFullDataSourceLoader fullDataSourceLoader;
	public CompleteFullDataSourceLoader getFullDataSourceLoader() { return this.fullDataSourceLoader; }
	
	
	public FullDataSourceResponseMessage() {}
	public FullDataSourceResponseMessage(@Nullable CompleteFullDataSource fullDataSource, DhServerLevel level)
	{
		this.fullDataSource = fullDataSource;
		this.level = level;
	}
	
	@Override
	public void encode0(ByteBuf out) throws IOException
	{
		if (this.encodeOptional(out, this.fullDataSource))
		{
			try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream())
			{
				DhDataOutputStream dhOutputStream = new DhDataOutputStream(outputStream);
				this.fullDataSource.writeToStream(dhOutputStream, this.level);
				dhOutputStream.flush();
				
				out.writeByte(this.fullDataSource.getDataFormatVersion());
				out.writeInt(outputStream.size());
				out.writeBytes(outputStream.toByteArray());
			}
		}
	}
	
	@Override
	public void decode0(ByteBuf in)
	{
		this.dataBuffer = this.decodeOptional(in, () ->
		{
			byte dataVersion = in.readByte();
			this.fullDataSourceLoader = (CompleteFullDataSourceLoader) AbstractFullDataSourceLoader.getLoader(CompleteFullDataSource.DATA_TYPE_NAME, dataVersion);
			return in.readBytes(in.readInt());
		});
	}
	
	@Nullable
	public synchronized CompleteFullDataSource getFullDataSource(DhSectionPos pos, IDhLevel level) throws IOException, InterruptedException
	{
		if (this.dataBuffer == null)
		{
			return null;
		}
		
		try (ByteBufInputStream inputStream = new ByteBufInputStream(this.dataBuffer))
		{
			return this.fullDataSourceLoader.loadData(pos, new DhDataInputStream(inputStream), level);
		}
		finally
		{
			this.dataBuffer.release();
		}
	}
	
	@Override
	public String toString()
	{
		return super.toString("dataBuffer=" + this.dataBuffer);
	}
	
}
