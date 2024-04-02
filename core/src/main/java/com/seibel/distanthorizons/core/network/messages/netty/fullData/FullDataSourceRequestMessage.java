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

package com.seibel.distanthorizons.core.network.messages.netty.fullData;

import com.seibel.distanthorizons.core.network.messages.netty.ILevelRelatedMessage;
import com.seibel.distanthorizons.core.network.netty.TrackableNettyMessage;
import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import io.netty.buffer.ByteBuf;

import javax.annotation.Nullable;

public class FullDataSourceRequestMessage extends TrackableNettyMessage implements ILevelRelatedMessage
{
	private int levelHashCode;
	
	public DhSectionPos sectionPos;
	
	/** Only present when requesting for changes. */
	@Nullable
	public Integer checksum;
	
	@Override
	public int getLevelHashCode() { return this.levelHashCode; }
	
	public FullDataSourceRequestMessage() {}
	public FullDataSourceRequestMessage(ILevelWrapper levelWrapper, DhSectionPos sectionPos, @Nullable Integer checksum)
	{
		// TODO Multiverse support
		this.levelHashCode = levelWrapper.getDimensionType().getDimensionName().hashCode();
		this.sectionPos = sectionPos;
		this.checksum = checksum;
	}

    @Override
    public void encode0(ByteBuf out)
	{
		out.writeInt(this.levelHashCode);
		this.sectionPos.encode(out);
		if (this.writeOptional(out, this.checksum))
		{
			out.writeInt(this.checksum);
		}
    }

    @Override
    public void decode0(ByteBuf in)
	{
		this.levelHashCode = in.readInt();
		this.sectionPos = INetworkObject.readToObject(DhSectionPos.zero(), in);
		this.checksum = this.readOptional(in, in::readInt);
    }
	
	@Override
	public String toString()
	{
		return super.toString(
				"dhSectionPos=" + this.sectionPos +
						", levelHashCode=" + this.levelHashCode +
						", checksum=" + this.checksum
		);
	}
	
}
