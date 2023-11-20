package com.seibel.distanthorizons.core.multiplayer.config;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;

import java.io.Closeable;
import java.util.ArrayList;

@SuppressWarnings({"rawtypes", "unchecked"})
public class MultiplayerConfigChangeListener implements Closeable
{
	private static final ConfigEntry[] CONFIG_ENTRIES = new ConfigEntry[] {
			Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius,
			Config.Client.Advanced.WorldGenerator.enableDistantGeneration,
			Config.Client.Advanced.Multiplayer.ServerNetworking.requestRateLimit,
			Config.Client.Advanced.Multiplayer.ServerNetworking.enableRealTimeUpdates,
			Config.Client.Advanced.Multiplayer.ServerNetworking.enablePostRelogUpdate
	};
	
	private final ArrayList<ConfigChangeListener> changeListeners = new ArrayList<>();
	
	public MultiplayerConfigChangeListener(Runnable runnable)
	{
		for (ConfigEntry entry : CONFIG_ENTRIES)
			changeListeners.add(new ConfigChangeListener(entry, ignored -> runnable.run()));
	}
	
	@Override
	public void close()
	{
		for (ConfigChangeListener changeListener : changeListeners)
			changeListener.close();
		changeListeners.clear();
	}
	
}
