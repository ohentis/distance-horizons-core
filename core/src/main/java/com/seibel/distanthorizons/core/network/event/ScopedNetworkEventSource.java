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

package com.seibel.distanthorizons.core.network.event;

import com.seibel.distanthorizons.core.network.messages.NetworkMessage;

import java.util.function.Consumer;

/** Provides a way to register network message handlers which are expected to be removed later. */
public final class ScopedNetworkEventSource extends NetworkEventSource
{
	public final NetworkEventSource parent;
	private boolean isClosed = false;
	
	private final Consumer<NetworkMessage> actualHandleMessageStable = this::handleMessage;
	
	
	public ScopedNetworkEventSource(NetworkEventSource parent)
	{
		this.parent = parent;
	}
	
	
	@Override
	public <T extends NetworkMessage> void registerHandler(Class<T> handlerClass, boolean throwIfMessageNotRegistered, Consumer<T> handlerImplementation)
	{
		if (this.isClosed)
		{
			return;
		}
		
		//noinspection unchecked
		this.parent.registerHandler(this, handlerClass, throwIfMessageNotRegistered, (Consumer<T>) this.actualHandleMessageStable);
		
		super.registerHandler(this, handlerClass, throwIfMessageNotRegistered, handlerImplementation);
	}
	
	
	@Override
	public void close()
	{
		this.isClosed = true;
		this.parent.removeAllHandlers(this);
	}
}