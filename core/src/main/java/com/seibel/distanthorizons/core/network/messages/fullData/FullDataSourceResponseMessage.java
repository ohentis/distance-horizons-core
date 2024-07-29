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
import com.seibel.distanthorizons.core.network.INetworkObject;
import com.seibel.distanthorizons.core.network.messages.TrackableMessage;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.Nullable;

/**
 * Response message, containing the requested full data source,
 * or nothing if requested in updates-only mode and the data was not updated.
 */
public class FullDataSourceResponseMessage extends TrackableMessage
{
	@Nullable
	public FullDataPayload payload;
	
	
	public FullDataSourceResponseMessage() { }
	public FullDataSourceResponseMessage(@Nullable FullDataPayload payload)
	{
		if (payload != null)
		{
			this.payload = payload;
		}
	}
	
	@Override
	public void encode0(ByteBuf out)
	{
		if (this.writeOptional(out, this.payload))
		{
			this.payload.encode(out);
		}
	}
	
	@Override
	public void decode0(ByteBuf in)
	{
		this.payload = this.readOptional(in, () -> INetworkObject.decodeToInstance(new FullDataPayload(), in));
	}
	
	
	@Override
	public MoreObjects.ToStringHelper toStringHelper()
	{
		return super.toStringHelper()
				.add("payload", this.payload);
	}
	
}