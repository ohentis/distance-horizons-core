package com.seibel.distanthorizons.core.multiplayer.config;

import com.seibel.distanthorizons.core.config.Config;
import io.netty.buffer.ByteBuf;

public class MultiplayerConfig extends AbstractMultiplayerConfig
{
	// IMPORTANT: Once you added/removed config fields, modify MultiplayerConfigChangeListener accordingly.
	
	public int renderDistanceRadius = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get();
	@Override public int getRenderDistanceRadius() { return this.renderDistanceRadius; }
	
	public boolean distantGenerationEnabled = Config.Client.Advanced.WorldGenerator.enableDistantGeneration.get();
	@Override public boolean isDistantGenerationEnabled() { return this.distantGenerationEnabled; }
	
	public int fullDataRequestConcurrencyLimit = Config.Client.Advanced.Multiplayer.ServerNetworking.fullDataRequestConcurrencyLimit.get();
	@Override public int getFullDataRequestConcurrencyLimit() { return this.fullDataRequestConcurrencyLimit; }
	
	public int genTaskPriorityRequestRateLimit = Config.Client.Advanced.Multiplayer.ServerNetworking.genTaskPriorityRequestRateLimit.get();
	@Override public int getGenTaskPriorityRequestRateLimit() { return this.genTaskPriorityRequestRateLimit; }
	
	public boolean realTimeUpdatesEnabled = Config.Client.Advanced.Multiplayer.ServerNetworking.enableRealTimeUpdates.get();
	@Override public boolean isRealTimeUpdatesEnabled() { return this.realTimeUpdatesEnabled; }
	
	public boolean postRelogUpdateEnabled = Config.Client.Advanced.Multiplayer.ServerNetworking.enablePostRelogUpdate.get();
	@Override public boolean isPostRelogUpdateEnabled() { return this.postRelogUpdateEnabled; }
	
	public int postRelogUpdateConcurrencyLimit = Config.Client.Advanced.Multiplayer.ServerNetworking.postRelogUpdateConcurrencyLimit.get();
	@Override public int getPostRelogUpdateConcurrencyLimit() { return this.postRelogUpdateConcurrencyLimit; }
	
	@Override
	public void decode(ByteBuf in)
	{
		this.renderDistanceRadius = in.readInt();
		this.distantGenerationEnabled = in.readBoolean();
		this.fullDataRequestConcurrencyLimit = in.readInt();
		this.genTaskPriorityRequestRateLimit = in.readInt();
		this.realTimeUpdatesEnabled = in.readBoolean();
		this.postRelogUpdateEnabled = in.readBoolean();
		this.postRelogUpdateConcurrencyLimit = in.readInt();
	}
	
	@Override public String toString()
	{
		return "MultiplayerConfig{" +
				"renderDistance=" + this.renderDistanceRadius +
				", distantGenerationEnabled=" + this.distantGenerationEnabled +
				", fullDataRequestConcurrencyLimit=" + this.fullDataRequestConcurrencyLimit +
				", genTaskPriorityRequestRateLimit=" + this.genTaskPriorityRequestRateLimit +
				", realTimeUpdatesEnabled=" + this.realTimeUpdatesEnabled +
				", postRelogUpdatesEnabled=" + this.postRelogUpdateEnabled +
				", postRelogUpdateConcurrencyLimit=" + this.postRelogUpdateConcurrencyLimit +
				'}';
	}
	
}
