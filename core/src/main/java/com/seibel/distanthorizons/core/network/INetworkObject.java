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

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.Contract;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.*;

/** The base for any object that can be sent over the network. */
public interface INetworkObject
{
	/** Serializes this object into the given {@link ByteBuf} */
	void encode(ByteBuf out);
	/** Populates this object's from the given {@link ByteBuf} */
	void decode(ByteBuf in);
	
	
	
	//========================//
	// default/static methods //
	//========================//
	
	/** 
	 * @param obj the empty object that will be populated by the incoming {@link ByteBuf}
	 */
	static <T extends INetworkObject> T decodeToInstance(T obj, ByteBuf inputByteBuf)
	{
		obj.decode(inputByteBuf);
		return obj;
	}
	
	
	
	@Contract("_, null -> false; _, !null -> true")
	default boolean writeOptional(ByteBuf outputByteBuf, Object value)
	{
		boolean isNull = value != null;
		outputByteBuf.writeBoolean(isNull);
		return isNull;
	}
	
	@Nullable
	default <T> T readOptional(ByteBuf inputByteBuf, Supplier<T> decoder)
	{
		return inputByteBuf.readBoolean()
				? decoder.get() 
				: null;
	}
	default void readOptional(ByteBuf inputByteBuf, Runnable decoder)
	{
		if (inputByteBuf.readBoolean())
		{
			decoder.run();
		}
	}
	
	
	// strings //
	
	default void writeString(String inputString, ByteBuf outputByteBuf) { INetworkObject.writeStringStatic(inputString, outputByteBuf); }
	static void writeStringStatic(String inputString, ByteBuf outputByteBuf)
	{
		byte[] bytes = inputString.getBytes(StandardCharsets.UTF_8);
		outputByteBuf.writeShort(bytes.length);
		outputByteBuf.writeBytes(bytes);
	}
	
	default String readString(ByteBuf inputByteBuf) { return INetworkObject.readStringStatic(inputByteBuf); }
	static String readStringStatic(ByteBuf inputByteBuf)
	{
		int length = inputByteBuf.readUnsignedShort();
		return inputByteBuf.readSlice(length).toString(StandardCharsets.UTF_8);
	}
	
	
	// collections //
	
	default <T> void writeCollection(ByteBuf outputByteBuf, Collection<T> collection)
	{
		outputByteBuf.writeInt(collection.size());
		this.writeFixedLengthCollection(outputByteBuf, collection);
	}
	default <T> void writeFixedLengthCollection(ByteBuf outputByteBuf, Collection<T> collection)
	{
		for (T item : collection)
		{
			Codec codec = Codec.getCodec(item.getClass());
			codec.encode.accept(item, outputByteBuf);
		}
	}
	
	default <T> void readCollection(ByteBuf inputByteBuf, Collection<T> collection, Supplier<T> innerValueConstructor)
	{
		int size = inputByteBuf.readInt();
		
		Codec codec = null;
		for (int i = 0; i < size; i++)
		{
			T item = innerValueConstructor.get();
			
			if (codec == null)
			{
				codec = Codec.getCodec(item.getClass());
			}
			//noinspection unchecked
			item = (T) codec.decode.apply(item, inputByteBuf);
			
			collection.add(item);
		}
	}
	
	default <K, V> void readMap(ByteBuf inputByteBuf, Map<K, V> map, Supplier<K> keySupplier, Supplier<V> valueSupplier)
	{
		ArrayList<Map.Entry<K, V>> entryList = new ArrayList<>();
		
		this.readCollection(inputByteBuf, entryList, () -> new AbstractMap.SimpleEntry<>(keySupplier.get(), valueSupplier.get()));
		for (Map.Entry<K, V> entry : entryList)
		{
			map.put(entry.getKey(), entry.getValue());
		}
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	/**
	 * Defines (de)serialization for different classes,
	 * specifically for base classes like {@link Integer}, {@link Boolean}, and {@link String}. <br><br>
	 * 
	 * Should only be used for non-editable classes;
	 * otherwise, you may want to implement {@link INetworkObject} and use its methods where applicable.
	 */
	class Codec
	{
		private static final ConcurrentMap<Class<?>, Codec> CODEC_MAP = new ConcurrentHashMap<Class<?>, Codec>()
		{{
			// Primitives must be added manually here
			this.put(Integer.class, new Codec((obj, outByteBuff) -> outByteBuff.writeInt((int)obj), (obj, inByteBuff) -> inByteBuff.readInt()));
			this.put(Boolean.class, new Codec((obj, outByteBuff) -> outByteBuff.writeBoolean((boolean) obj), (obj, inByteBuff) -> inByteBuff.readBoolean()));
			this.put(String.class, new Codec((obj, outByteBuff) -> INetworkObject.writeStringStatic((String) obj, outByteBuff), (obj, inByteBuff) -> INetworkObject.readStringStatic(inByteBuff)));
			
			this.put(INetworkObject.class, new Codec(INetworkObject::encode, INetworkObject::decodeToInstance));
			this.put(Map.Entry.class, new Codec(
					(obj, outByteBuff) -> 
					{
						Map.Entry<?, ?> entry = (Entry<?, ?>) obj;
						getCodec(entry.getKey().getClass()).encode.accept(entry.getKey(), outByteBuff);
						getCodec(entry.getValue().getClass()).encode.accept(entry.getValue(), outByteBuff);
					},
					(obj, inByteBuff) -> 
					{
						Map.Entry<?, ?> entry = (Entry<?, ?>) obj;
						return new SimpleEntry<>(
							getCodec(entry.getKey().getClass()).decode.apply(entry.getKey(), inByteBuff),
							getCodec(entry.getValue().getClass()).decode.apply(entry.getValue(), inByteBuff)
						);
					}
			));
		}};
		
		
		public final BiConsumer<Object, ByteBuf> encode;
		public final BiFunction<Object, ByteBuf, Object> decode;
		
		
		
		//=============//
		// constructor //
		//=============//
		
		@SuppressWarnings("unchecked")
		public <T> Codec(BiConsumer<T, ByteBuf> encode, BiFunction<T, ByteBuf, T> decode)
		{
			this.encode = (BiConsumer<Object, ByteBuf>) encode;
			this.decode = (BiFunction<Object, ByteBuf, Object>) decode;
		}
		
		
		
		//================//
		// static methods //
		//================//
		
		public static <T> Codec getCodec(Class<T> clazz)
		{
			return CODEC_MAP.computeIfAbsent(clazz, ignored -> 
			{
				// TODO when would this ever return true?
				for (Map.Entry<Class<?>, Codec> entry : CODEC_MAP.entrySet())
				{
					if (entry.getKey().isAssignableFrom(clazz))
					{
						return entry.getValue();
					}
				}
				
				throw new AssertionError("Class has no compatible codec: " + clazz.getSimpleName());
			});
		}
		
	}
}