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

import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import io.netty.buffer.ByteBuf;

import javax.annotation.Nullable;

public class FullDataSourceRequestMessage extends FutureTrackableNetworkMessage
{
	public DhSectionPos dhSectionPos;

	public FullDataSourceRequestMessage() {}

	public FullDataSourceRequestMessage(DhSectionPos dhSectionPos)
	{
		this.dhSectionPos = dhSectionPos;
	}

    @Override
    public void encode0(ByteBuf out)
	{
		dhSectionPos.encode(out);
    }

    @Override
    public void decode0(ByteBuf in)
	{
		dhSectionPos = INetworkObject.decodeStatic(DhSectionPos.zero(), in);
    }
	
	@Override
	public String toString()
	{
		return super.toString("dhSectionPos=" + dhSectionPos);
	}
	
}
