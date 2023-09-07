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

package com.seibel.distanthorizons.core.network.protocol;

import com.seibel.distanthorizons.core.network.messages.base.ExceptionMessage;
import io.netty.buffer.ByteBuf;

import java.util.concurrent.atomic.AtomicInteger;

public abstract class FutureTrackableNetworkMessage extends NetworkMessage
{
	private static final AtomicInteger lastId = new AtomicInteger();
	// Only low 32 bits are sent (high bits are used for identifying a channel this request was sent from by remote peer)
	public long futureId = lastId.incrementAndGet();
	
	public void sendResponse(FutureTrackableNetworkMessage responseMessage)
	{
		responseMessage.futureId = futureId;
		getChannelContext().writeAndFlush(responseMessage);
	}
	
	public void sendResponse(Exception e)
	{
		sendResponse(new ExceptionMessage(e));
	}
	
	@Override public final void encode(ByteBuf out)
	{
		try
		{
			out.writeInt((int)futureId);
			this.encode0(out);
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}
	
	@Override public final void decode(ByteBuf in)
	{
		try
		{
			futureId = in.readInt();
			this.decode0(in);
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}
	
	protected abstract void encode0(ByteBuf out) throws Exception;
	protected abstract void decode0(ByteBuf in) throws Exception;
}
