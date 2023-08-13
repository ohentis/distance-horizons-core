package com.seibel.distanthorizons.core.network.protocol;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.*;

public interface INetworkObject
{
	void encode(ByteBuf out);
	
	void decode(ByteBuf in);
	
	static <T extends INetworkObject> T decodeStatic(T obj, ByteBuf inputByteBuf)
	{
		obj.decode(inputByteBuf);
		return obj;
	}
	
	default void encodeString(String inputString, ByteBuf outputByteBuf)
	{
		byte[] bytes = inputString.getBytes(StandardCharsets.UTF_8);
		outputByteBuf.writeShort(bytes.length);
		outputByteBuf.writeBytes(bytes);
	}
	
	default String decodeString(ByteBuf inputByteBuf)
	{
		int length = inputByteBuf.readShort();
		return inputByteBuf.readBytes(length).toString(StandardCharsets.UTF_8);
	}
	
	default <T> void encodeCollection(ByteBuf outputByteBuf, Collection<T> collection)
	{
		outputByteBuf.writeInt(collection.size());
		
		Codec codec = null;
		for (T item : collection)
		{
			if (codec == null)
				codec = Codec.getCodec(item.getClass());
			codec.encode.accept(item, outputByteBuf);
		}
	}
	
	default <T> void decodeCollection(ByteBuf inputByteBuf, Collection<T> collection, Supplier<T> innerValueConstructor)
	{
		int size = inputByteBuf.readInt();
		
		Codec codec = null;
		for (int i = 0; i < size; i++)
		{
			T item = innerValueConstructor.get();
			
			if (codec == null)
				codec = Codec.getCodec(item.getClass());
			item = (T) codec.decode.apply(item, inputByteBuf);
			
			collection.add(item);
		}
	}
	
	default <K, V> void decodeMap(ByteBuf inputByteBuf, Map<K, V> map, Supplier<K> keySupplier, Supplier<V> valueSupplier)
	{
		ArrayList<Map.Entry<K, V>> entryList = new ArrayList<>();
		
		decodeCollection(inputByteBuf, entryList, () -> new AbstractMap.SimpleEntry<>(keySupplier.get(), valueSupplier.get()));
		for (Map.Entry<K, V> entry : entryList)
			map.put(entry.getKey(), entry.getValue());
	}
	
	/** Should only be used for non-editable classes;
	 * otherwise, you may want to implement {@link INetworkObject} and use its method where applicable. */
	class Codec
	{
		private static final ConcurrentMap<Class<?>, Codec> codecMap = new ConcurrentHashMap<Class<?>, Codec>()
		{{
			// Primitives must be added manually here
			put(Integer.class, new Codec((obj, out) -> out.writeInt((int)obj), (obj, in) -> in.readInt()));
			
			put(INetworkObject.class, new Codec(INetworkObject::encode, INetworkObject::decodeStatic));
			put(Map.Entry.class, new Codec(
					(obj, out) -> {
						Map.Entry<?, ?> entry = (Entry<?, ?>) obj;
						getCodec(entry.getKey().getClass()).encode.accept(entry.getKey(), out);
						getCodec(entry.getValue().getClass()).encode.accept(entry.getValue(), out);
					},
					(obj, in) -> {
						Map.Entry<?, ?> entry = (Entry<?, ?>) obj;
						return new SimpleEntry<>(
								getCodec(entry.getKey().getClass()).decode.apply(entry.getKey(), in),
								getCodec(entry.getValue().getClass()).decode.apply(entry.getValue(), in)
						);
					}
			));
		}};
		
		public final BiConsumer<Object, ByteBuf> encode;
		public final BiFunction<Object, ByteBuf, Object> decode;
		
		public <T> Codec(BiConsumer<T, ByteBuf> encode, BiFunction<T, ByteBuf, T> decode)
		{
			this.encode = (BiConsumer<Object, ByteBuf>) encode;
			this.decode = (BiFunction<Object, ByteBuf, Object>) decode;
		}
		
		public static Codec getCodec(Class<?> clazz) {
			return codecMap.computeIfAbsent(clazz, ignored -> {
				for (Map.Entry<Class<?>, Codec> entry : codecMap.entrySet())
				{
					if (entry.getKey().isAssignableFrom(clazz))
						return entry.getValue();
				}
				
				throw new AssertionError("Class has no compatible codec: "+clazz.getSimpleName());
			});
		}
	}
}
