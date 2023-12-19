package com.seibel.distanthorizons.core.multiplayer.config;

import com.seibel.distanthorizons.core.config.Config;
import io.netty.buffer.ByteBuf;

public class MultiplayerConfig extends AbstractMultiplayerConfig
{
	// IMPORTANT: Once you added/removed config fields, modify MultiplayerConfigChangeListener accordingly.
	
	public int renderDistanceRadius = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get();
	@Override public int getRenderDistanceRadius() { return renderDistanceRadius; }
	
	public boolean distantGenerationEnabled = Config.Client.Advanced.WorldGenerator.enableDistantGeneration.get();
	@Override public boolean isDistantGenerationEnabled() { return distantGenerationEnabled; }
	
	public int fullDataRequestConcurrencyLimit = Config.Client.Advanced.Multiplayer.ServerNetworking.fullDataRequestConcurrencyLimit.get();
	@Override public int getFullDataRequestConcurrencyLimit() { return fullDataRequestConcurrencyLimit; }
	
	public int genTaskPriorityRequestRateLimit = Config.Client.Advanced.Multiplayer.ServerNetworking.genTaskPriorityRequestRateLimit.get();
	@Override public int getGenTaskPriorityRequestRateLimit() { return genTaskPriorityRequestRateLimit; }
	
	public boolean realTimeUpdatesEnabled = Config.Client.Advanced.Multiplayer.ServerNetworking.enableRealTimeUpdates.get();
	@Override public boolean isRealTimeUpdatesEnabled() { return realTimeUpdatesEnabled; }
	
	public boolean postRelogUpdateEnabled = false; // Config.Client.Advanced.Multiplayer.ServerNetworking.enablePostRelogUpdate.get();
	@Override public boolean isPostRelogUpdateEnabled() { return postRelogUpdateEnabled; }
	
	@Override
	public void decode(ByteBuf in)
	{
		this.renderDistanceRadius = in.readInt();
		this.distantGenerationEnabled = in.readBoolean();
		this.fullDataRequestConcurrencyLimit = in.readInt();
		this.genTaskPriorityRequestRateLimit = in.readInt();
		this.realTimeUpdatesEnabled = in.readBoolean();
		this.postRelogUpdateEnabled = in.readBoolean();
	}
	
	@Override public String toString()
	{
		return "MultiplayerConfig{" +
				"renderDistance=" + renderDistanceRadius +
				", distantGenerationEnabled=" + distantGenerationEnabled +
				", fullDataRequestConcurrencyLimit=" + fullDataRequestConcurrencyLimit +
				", genTaskPriorityRequestRateLimit=" + genTaskPriorityRequestRateLimit +
				", realTimeUpdatesEnabled=" + realTimeUpdatesEnabled +
				", postRelogUpdatesEnabled=" + postRelogUpdateEnabled +
				'}';
	}
	
}
