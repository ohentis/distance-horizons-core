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

package com.seibel.distanthorizons.core.network.messages;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.seibel.distanthorizons.core.network.protocol.INetworkObject;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public abstract class AbstractMessageRegistry<TMessage extends INetworkObject>
{
	private final Map<Integer, Supplier<? extends TMessage>> idToSupplier = new HashMap<>();
	private final BiMap<Class<? extends TMessage>, Integer> classToId = HashBiMap.create();
	
	
	
	protected <T extends TMessage> void registerMessage(Class<T> clazz, Supplier<T> supplier)
	{
		int id = this.idToSupplier.size() + 1;
		this.idToSupplier.put(id, supplier);
		this.classToId.put(clazz, id);
	}
	
	public TMessage createMessage(int messageId) throws IllegalArgumentException
	{
		try
		{
			return this.idToSupplier.get(messageId).get();
		}
		catch (NullPointerException e)
		{
			throw new IllegalArgumentException("Invalid message ID: " + messageId);
		}
	}
	
	@SuppressWarnings("unchecked")
	public int getMessageId(TMessage message)
	{
		return this.getMessageId((Class<? extends TMessage>) message.getClass());
	}
	
	public int getMessageId(Class<? extends TMessage> messageClass)
	{
		try
		{
			return this.classToId.get(messageClass);
		}
		catch (NullPointerException e)
		{
			throw new IllegalArgumentException("Message does not have ID assigned to it: " + messageClass.getSimpleName());
		}
	}
	
}
