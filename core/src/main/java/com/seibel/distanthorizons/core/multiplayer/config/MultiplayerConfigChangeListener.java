package com.seibel.distanthorizons.core.multiplayer.config;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;

import java.io.Closeable;

public class MultiplayerConfigChangeListener implements Closeable
{
	private final ConfigChangeListener<Integer> renderDistance;
	private final ConfigChangeListener<Integer> rateLimit;
	private final ConfigChangeListener<Boolean> enableRealTimeUpdates;
	private final ConfigChangeListener<Boolean> enablePostRelogUpdate;
	
	public MultiplayerConfigChangeListener(Runnable runnable)
	{
		renderDistance = new ConfigChangeListener<>(Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistance, ignored -> runnable.run());
		rateLimit = new ConfigChangeListener<>(Config.Client.Advanced.Multiplayer.ServerNetworking.requestRateLimit, ignored -> runnable.run());
		enableRealTimeUpdates = new ConfigChangeListener<>(Config.Client.Advanced.Multiplayer.ServerNetworking.enableRealTimeUpdates, ignored -> runnable.run());
		enablePostRelogUpdate = new ConfigChangeListener<>(Config.Client.Advanced.Multiplayer.ServerNetworking.enablePostRelogUpdate, ignored -> runnable.run());
	}
	
	@Override
	public void close()
	{
		renderDistance.close();
		rateLimit.close();
		enableRealTimeUpdates.close();
		enablePostRelogUpdate.close();
	}
	
}
