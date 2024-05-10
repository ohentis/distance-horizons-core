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

package com.seibel.distanthorizons.core.network.netty;

import com.seibel.distanthorizons.core.network.netty.INettyConnection;
import com.seibel.distanthorizons.core.network.protocol.INetworkObject;

import javax.annotation.Nullable;

public abstract class NettyMessage implements INetworkObject
{
	private INettyConnection connection = null;
	
	public boolean warnWhenUnhandled() { return true; }
	
	public INettyConnection getConnection()
	{
		return this.connection;
	}
	
	public void setConnection(INettyConnection connection)
	{
		if (this.connection != null)
		{
			throw new IllegalStateException("Channel context cannot be changed after initial setting.");
		}
		this.connection = connection;
	}
	
	@Override
	public String toString()
	{
		return this.toString(" ");
	}
	
	protected String toString(@Nullable String extraData)
	{
		return this.getClass().getSimpleName() + "{" + extraData + '}';
	}
	
}