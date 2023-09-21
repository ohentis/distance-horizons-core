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

import com.seibel.distanthorizons.core.network.messages.base.ILevelRelatedMessage;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import io.netty.buffer.ByteBuf;

public class FullDataSourceRequestMessage extends FutureTrackableNetworkMessage implements ILevelRelatedMessage
{
	public DhSectionPos dhSectionPos;
	private int levelHashCode;
	@Override public int getLevelHashCode() { return levelHashCode; }
	
	public FullDataSourceRequestMessage() {}

	public FullDataSourceRequestMessage(ILevelWrapper levelWrapper, DhSectionPos dhSectionPos)
	{
		// TODO Multiverse support
		this.levelHashCode = levelWrapper.getDimensionType().getDimensionName().hashCode();
		this.dhSectionPos = dhSectionPos;
	}

    @Override
    public void encode0(ByteBuf out)
	{
		out.writeInt(levelHashCode);
		dhSectionPos.encode(out);
    }

    @Override
    public void decode0(ByteBuf in)
	{
		levelHashCode = in.readInt();
		dhSectionPos = INetworkObject.decodeStatic(DhSectionPos.zero(), in);
    }
	
	@Override
	public String toString()
	{
		return super.toString(
				"dhSectionPos=" + dhSectionPos +
				", levelHashCode=" + levelHashCode
		);
	}
	
}
