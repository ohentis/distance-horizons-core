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

package com.seibel.distanthorizons.core.network.messages.netty.fullData.generation;

import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.network.messages.netty.ILevelRelatedMessage;
import com.seibel.distanthorizons.core.network.netty.TrackableNettyMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

public class GenTaskPriorityRequestMessage extends TrackableNettyMessage implements ILevelRelatedMessage
{
	public List<DhSectionPos> posList = new ArrayList<>();
	
	private String levelName;
	@Override
	public String getLevelName() { return this.levelName; }
	
	
	public GenTaskPriorityRequestMessage() { }
	public GenTaskPriorityRequestMessage(List<DhSectionPos> posList, IDhLevel level)
	{
		this.posList = posList;
		
		// TODO Multiverse support
		this.levelName = level.getLevelWrapper().getDimensionType().getDimensionName();
	}
	
	@Override
	protected void encode0(ByteBuf out)
	{
		this.writeString(this.levelName, out);
		this.writeCollection(out, this.posList);
	}
	
	@Override
	protected void decode0(ByteBuf in)
	{
		this.levelName = this.readString(in);
		this.readCollection(in, this.posList, DhSectionPos::zero);
	}
	
	@Override public String toString()
	{
		return super.toString("posList=" + this.posList);
	}
	
}