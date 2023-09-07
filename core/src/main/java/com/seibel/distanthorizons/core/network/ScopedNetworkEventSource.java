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

package com.seibel.distanthorizons.core.network;

import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;

import java.util.function.Consumer;

/** Provides a way to register network message handlers which are expected to be removed later. */
public final class ScopedNetworkEventSource<TParent extends NetworkEventSource> extends NetworkEventSource
{
	public final TParent parent;
	private boolean isClosed = false;
	
	public ScopedNetworkEventSource(TParent parent)
	{
		this.parent = parent;
	}
	
	@Override
	public <T extends NetworkMessage> void registerHandler(Class<T> handlerClass, Consumer<T> handlerImplementation)
	{
		if (isClosed) return;
		
		if (!this.hasHandler(handlerClass))
		{
			parent.registerHandler(handlerClass, this::handleMessage);
		}
		
		super.registerHandler(handlerClass, handlerImplementation);
	}
	
	@Override
	public void close()
	{
		isClosed = true;
		for (Class<? extends NetworkMessage> handlerClass : this.handlers.keySet())
		{
			parent.removeHandler(handlerClass, this::handleMessage);
		}
	}
}
