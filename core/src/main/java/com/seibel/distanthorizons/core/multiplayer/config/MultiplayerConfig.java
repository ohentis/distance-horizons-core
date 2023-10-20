package com.seibel.distanthorizons.core.multiplayer.config;

import com.seibel.distanthorizons.core.config.Config;
import io.netty.buffer.ByteBuf;

public class MultiplayerConfig extends AbstractMultiplayerConfig
{
	public int renderDistance = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get();
	@Override public int getRenderDistance() { return renderDistance; }
	
	public int fullDataRequestRateLimit = Config.Client.Advanced.Multiplayer.ServerNetworking.requestRateLimit.get();
	@Override public int getFullDataRequestRateLimit() { return fullDataRequestRateLimit; }
	
	public boolean realTimeUpdatesEnabled = Config.Client.Advanced.Multiplayer.ServerNetworking.enableRealTimeUpdates.get();
	@Override public boolean isRealTimeUpdatesEnabled() { return realTimeUpdatesEnabled; }
	
	public boolean postRelogUpdateEnabled = Config.Client.Advanced.Multiplayer.ServerNetworking.enablePostRelogUpdate.get();
	@Override public boolean isPostRelogUpdateEnabled() { return postRelogUpdateEnabled; }
	
	@Override
	public void decode(ByteBuf in)
	{
		this.renderDistance = in.readInt();
		this.fullDataRequestRateLimit = in.readInt();
		this.realTimeUpdatesEnabled = in.readBoolean();
		this.postRelogUpdateEnabled = in.readBoolean();
	}
	
	@Override public String toString()
	{
		return "MultiplayerConfig{" +
				"renderDistance=" + renderDistance +
				", fullDataRequestRateLimit=" + fullDataRequestRateLimit +
				", realTimeUpdatesEnabled=" + realTimeUpdatesEnabled +
				", postRelogUpdatesEnabled=" + postRelogUpdateEnabled +
				'}';
	}
	
}
