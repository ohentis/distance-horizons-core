package com.seibel.distanthorizons.core.multiplayer.config;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;

import java.io.Closeable;

public class MultiplayerConfigChangeListener implements Closeable
{
	private final ConfigChangeListener<Integer> renderDistanceRadius;
	private final ConfigChangeListener<Boolean> enableDistantGeneration;
	private final ConfigChangeListener<Integer> requestRateLimit;
	private final ConfigChangeListener<Boolean> enableRealTimeUpdates;
	private final ConfigChangeListener<Boolean> enablePostRelogUpdate;
	
	public MultiplayerConfigChangeListener(Runnable runnable)
	{
		renderDistanceRadius = new ConfigChangeListener<>(Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius, ignored -> runnable.run());
		enableDistantGeneration = new ConfigChangeListener<>(Config.Client.Advanced.WorldGenerator.enableDistantGeneration, ignored -> runnable.run());
		requestRateLimit = new ConfigChangeListener<>(Config.Client.Advanced.Multiplayer.ServerNetworking.requestRateLimit, ignored -> runnable.run());
		enableRealTimeUpdates = new ConfigChangeListener<>(Config.Client.Advanced.Multiplayer.ServerNetworking.enableRealTimeUpdates, ignored -> runnable.run());
		enablePostRelogUpdate = new ConfigChangeListener<>(Config.Client.Advanced.Multiplayer.ServerNetworking.enablePostRelogUpdate, ignored -> runnable.run());
	}
	
	@Override
	public void close()
	{
		renderDistanceRadius.close();
		enableDistantGeneration.close();
		requestRateLimit.close();
		enableRealTimeUpdates.close();
		enablePostRelogUpdate.close();
	}
	
}
