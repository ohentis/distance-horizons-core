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

import com.google.common.base.MoreObjects;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.network.messages.TrackableMessage;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.Nullable;

/**
 * Response message, containing the requested full data source,
 * or nothing if requested in updates-only mode and the data was not updated.
 */
public class FullDataSourceResponseMessage extends TrackableMessage implements IFullDataPayloadMessage
{
	@Nullable
	public Integer dtoBufferId;
	@Override @Nullable
	public Integer getDtoBufferId() { return this.dtoBufferId; }
	@Override
	public void setDtoBufferId(int bufferId) { this.dtoBufferId = bufferId; }
	
	public ByteBuf dtoBuffer;
	@Override
	public ByteBuf getDtoBuffer() { return this.dtoBuffer; }
	@Override
	public void setDtoBuffer(ByteBuf buffer) { this.dtoBuffer = buffer; }
	
	
	public FullDataSourceResponseMessage() { }
	public FullDataSourceResponseMessage(@Nullable FullDataSourceV2 fullDataSource)
	{
		if (fullDataSource != null)
		{
			this.createCompressedDtoBuffer(fullDataSource);
		}
	}
	
	@Override
	public void encode0(ByteBuf out)
	{
		if (this.writeOptional(out, this.dtoBufferId))
		{
			out.writeInt(this.dtoBufferId);
			this.dtoBuffer.release();
		}
	}
	
	@Override
	public void decode0(ByteBuf in)
	{
		this.dtoBufferId = this.readOptional(in, in::readInt);
	}
	
	
	@Override
	public MoreObjects.ToStringHelper toStringHelper()
	{
		return super.toStringHelper()
				.add("dtoBufferId", this.dtoBufferId)
				.add("dtoBuffer", this.dtoBuffer);
	}
	
}