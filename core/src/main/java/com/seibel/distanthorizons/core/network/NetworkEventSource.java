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

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.messages.AbstractMessageRegistry;
import com.seibel.distanthorizons.core.network.messages.ICloseEvent;
import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import com.seibel.distanthorizons.coreapi.ModInfo;
import org.apache.logging.log4j.LogManager;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public abstract class NetworkEventSource<TMessage extends INetworkObject>
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	protected final ConcurrentMap<Class<? extends TMessage>, Set<Consumer<TMessage>>> handlers = new ConcurrentHashMap<>();
	
	
	protected final AbstractMessageRegistry<TMessage> messageRegistry;
	public NetworkEventSource(AbstractMessageRegistry<TMessage> messageRegistry)
	{
		this.messageRegistry = messageRegistry;
	}
	
	protected final void handleMessage(TMessage message)
	{
		boolean handled = false;
		
		Set<Consumer<TMessage>> handlerList = this.handlers.get(message.getClass());
		if (handlerList != null)
		{
			for (Consumer<TMessage> handler : handlerList)
			{
				handled = true;
				handler.accept(message);
			}
		}
		
		handled |= this.tryHandleMessage(message);
		
		if (!handled && ModInfo.IS_DEV_BUILD)
		{
			LOGGER.warn("Unhandled message: " + message);
		}
	}
	
	protected boolean tryHandleMessage(TMessage message)
	{
		// By default, messages are handled only by their direct handlers.
		return false;
	}
	
	public <T extends TMessage> void registerHandler(Class<T> handlerClass, Consumer<T> handlerImplementation)
	{
		//noinspection unchecked
		this.handlers.computeIfAbsent(handlerClass, missingHandlerClass ->
				{
					// Will throw if the handler class is not found and not a CloseEvent
					if (!ICloseEvent.class.isAssignableFrom(missingHandlerClass))
					{
						this.messageRegistry.getMessageId(handlerClass);
					}
					return ConcurrentHashMap.newKeySet();
				})
				.add((Consumer<TMessage>) handlerImplementation);
	}
	
	protected boolean hasHandler(Class<? extends TMessage> handlerClass)
	{
		return this.handlers.containsKey(handlerClass);
	}
	
	protected <T extends TMessage> void removeHandler(Class<T> handlerClass, Consumer<T> handlerImplementation)
	{
		this.handlers.computeIfAbsent(handlerClass, missingHandlerClass -> new HashSet<>())
				.remove(handlerImplementation);
	}
	
	public void close()
	{
		this.handlers.clear();
	}
}
