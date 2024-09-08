package com.seibel.distanthorizons.core.multiplayer.config;

import com.google.common.base.MoreObjects;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.network.INetworkObject;
import io.netty.buffer.ByteBuf;

import java.io.Closeable;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.seibel.distanthorizons.core.config.Config.Client.Advanced.*;

public class SessionConfig implements INetworkObject
{
	private static final LinkedHashMap<String, Entry> CONFIG_ENTRIES = new LinkedHashMap<>();
	private static <T> void registerConfigEntry(ConfigEntry<T> configEntry, BiFunction<T, T, T> valueConstrainer)
	{
		CONFIG_ENTRIES.put(Objects.requireNonNull(configEntry.getServersideShortName()), new Entry(configEntry, valueConstrainer));
	}
	
	private final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
	public SessionConfig constrainingConfig;
	
	
	static
	{
		// Note: config values are ordered by serversideShortName when transmitted
		
		registerConfigEntry(Graphics.Quality.lodChunkRenderDistanceRadius, Math::min);
		
		registerConfigEntry(WorldGenerator.enableDistantGeneration, (x, y) -> x && y);
		registerConfigEntry(Multiplayer.ServerNetworking.generationRequestRateLimit, Math::min);
		
		registerConfigEntry(Multiplayer.ServerNetworking.enableRealTimeUpdates, (x, y) -> x && y);
		
		registerConfigEntry(Multiplayer.ServerNetworking.synchronizeOnLogin, (x, y) -> x && y);
		registerConfigEntry(Multiplayer.ServerNetworking.syncOnLoginRateLimit, Math::min);
	}
	
	public int getRenderDistanceRadius() { return this.getValue(Graphics.Quality.lodChunkRenderDistanceRadius); }
	public boolean isDistantGenerationEnabled() { return this.getValue(WorldGenerator.enableDistantGeneration); }
	public int getGenerationRequestRateLimit() { return this.getValue(Multiplayer.ServerNetworking.generationRequestRateLimit); }
	public boolean isRealTimeUpdatesEnabled() { return this.getValue(Multiplayer.ServerNetworking.enableRealTimeUpdates); }
	public boolean getSynchronizeOnLogin() { return this.getValue(Multiplayer.ServerNetworking.synchronizeOnLogin); }
	public int getSyncOnLoginRateLimit() { return this.getValue(Multiplayer.ServerNetworking.syncOnLoginRateLimit); }
	
	
	@SuppressWarnings("unchecked")
	private <T> T getValue(String name)
	{
		Entry entry = CONFIG_ENTRIES.get(name);
		
		T value = (T) this.values.get(name);
		if (value == null)
		{
			value = (T) entry.supplier.get();
		}
		
		return (this.constrainingConfig != null
				? (T) entry.valueConstrainer.apply(value, this.constrainingConfig.getValue(name))
				: value);
	}
	private <T> T getValue(ConfigEntry<T> configEntry)
	{
		return this.getValue(configEntry.getServersideShortName());
	}
	
	private Map<String, ?> getValues()
	{
		return CONFIG_ENTRIES.keySet().stream().collect(Collectors.toMap(
				Function.identity(),
				this::getValue,
				(x, y) -> x,
				LinkedHashMap::new
		));
	}
	
	
	@Override
	public void encode(ByteBuf out)
	{
		this.writeFixedLengthCollection(out, this.getValues().values());
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		for (String key : CONFIG_ENTRIES.keySet())
		{
			Object currentValue = this.getValue(key);
			Object newValue = Codec.getCodec(currentValue.getClass()).decode.apply(currentValue, in);
			this.values.put(key, newValue);
		}
	}
	
	
	@Override
	public String toString()
	{
		return MoreObjects.toStringHelper(this)
				.add("values", this.getValues())
				.toString();
	}
	
	
	private static class Entry
	{
		public final ConfigEntry<Object> supplier;
		public final BiFunction<Object, Object, Object> valueConstrainer;
		
		@SuppressWarnings("unchecked")
		private <T> Entry(ConfigEntry<T> supplier, BiFunction<T, T, T> valueConstrainer)
		{
			this.supplier = (ConfigEntry<Object>) supplier;
			this.valueConstrainer = (BiFunction<Object, Object, Object>) valueConstrainer;
		}
		
	}
	
	public static class ChangeListener implements Closeable
	{
		private final ArrayList<ConfigChangeListener<?>> changeListeners;
		
		public ChangeListener(Runnable runnable)
		{
			this.changeListeners = new ArrayList<>(CONFIG_ENTRIES.size());
			for (Entry entry : CONFIG_ENTRIES.values())
			{
				this.changeListeners.add(new ConfigChangeListener<>(entry.supplier, ignored -> runnable.run()));
			}
		}
		
		@Override
		public void close()
		{
			for (ConfigChangeListener<?> changeListener : this.changeListeners)
			{
				changeListener.close();
			}
			this.changeListeners.clear();
		}
		
	}
	
}