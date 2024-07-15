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
import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.network.INetworkObject;
import com.seibel.distanthorizons.core.network.messages.ILevelRelatedMessage;
import com.seibel.distanthorizons.core.network.messages.NetworkMessage;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import io.netty.buffer.ByteBuf;

import java.io.IOException;

public class FullDataChunkMessage extends NetworkMessage
{
	public ByteBuf buffer;
	
	
	public FullDataChunkMessage() { }
	public FullDataChunkMessage(ByteBuf buffer)
	{
	}
	
	
	@Override
	public boolean warnWhenUnhandled() { return false; }
	
	@Override
	public void encode(ByteBuf out)
	{
		out.writeInt(this.buffer.writerIndex());
		this.buffer.resetReaderIndex();
		out.writeBytes(this.buffer);
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		this.buffer = in.readBytes(in.readInt());
	}
	
	
	@Override
	public MoreObjects.ToStringHelper toStringHelper()
	{
		return super.toStringHelper()
				.add("levelName", this.levelName)
				.add("dataSourceDto", this.dataSourceDto);
	}
	
}