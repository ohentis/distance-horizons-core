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
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import io.netty.buffer.ByteBuf;

import java.util.HashMap;
import java.util.Map;

public class GenTaskPriorityResponseMessage extends FutureTrackableNetworkMessage
{
	public Map<DhSectionPos, Integer> posList = new HashMap<>();
	
	public GenTaskPriorityResponseMessage() { }
	public GenTaskPriorityResponseMessage(Map<DhSectionPos, Integer> posList)
	{
		this.posList = posList;
	}
	
	@Override
	protected void encode0(ByteBuf out)
	{
		this.encodeCollection(out, this.posList.entrySet());
	}
	
	@Override
	protected void decode0(ByteBuf in)
	{
		this.decodeMap(in, this.posList, DhSectionPos::zero, () -> 0);
	}
	
	@Override public String toString()
	{
		return super.toString("posList=" + this.posList);
	}
	
}
