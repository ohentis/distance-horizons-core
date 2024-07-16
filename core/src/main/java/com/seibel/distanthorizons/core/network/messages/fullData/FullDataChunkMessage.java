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
import com.seibel.distanthorizons.core.network.messages.NetworkMessage;
import io.netty.buffer.ByteBuf;

public class FullDataChunkMessage extends NetworkMessage
{
	public int bufferId;
	public ByteBuf buffer;
	public boolean isFirst;
	
	
	public FullDataChunkMessage() { }
	public FullDataChunkMessage(int bufferId, boolean isFirst, ByteBuf buffer)
	{
		this.bufferId = bufferId;
		this.buffer = buffer;
		this.isFirst = isFirst;
	}
	
	
	@Override
	public boolean warnWhenUnhandled() { return false; }
	
	@Override
	public void encode(ByteBuf out)
	{
		out.writeInt(this.bufferId);
		
		out.writeInt(this.buffer.writerIndex());
		out.writeBytes(this.buffer.readerIndex(0));

		out.writeBoolean(this.isFirst);
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		this.bufferId = in.readInt();
		
		int bufferSize = in.readInt();
		this.buffer = in.readBytes(bufferSize);

		this.isFirst = in.readBoolean();
	}
	
	
	@Override
	public MoreObjects.ToStringHelper toStringHelper()
	{
		return super.toStringHelper()
				.add("bufferId", this.bufferId)
				.add("buffer", this.buffer)
				.add("isFirst", this.isFirst);
	}
	
}