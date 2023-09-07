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

import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import io.netty.buffer.ByteBuf;

import java.util.HashMap;
import java.util.Map;

public class FullDataChangeSummaryRequestMessage extends FutureTrackableNetworkMessage
{
	public Map<DhSectionPos, Integer> checksums = new HashMap<>();
	public int levelHashCode;

	public FullDataChangeSummaryRequestMessage() { }

	public FullDataChangeSummaryRequestMessage(ILevelWrapper levelWrapper, Map<DhSectionPos, Integer> checksums)
	{
		// TODO Multiverse support
		this.levelHashCode = levelWrapper.getDimensionType().getDimensionName().hashCode();
		this.checksums = checksums;
	}
	
    @Override
    public void encode0(ByteBuf out)
	{
		// TODO Multiverse support
		out.writeInt(levelHashCode);
		encodeCollection(out, checksums.entrySet());
    }

    @Override
    public void decode0(ByteBuf in)
	{
		levelHashCode = in.readInt();
		decodeMap(in, checksums, DhSectionPos::zero, () -> 0);
    }
	
	public boolean isLevelValid(ILevelWrapper levelWrapper)
	{
		return levelWrapper.getDimensionType().getDimensionName().hashCode() == levelHashCode;
	}
	
}
