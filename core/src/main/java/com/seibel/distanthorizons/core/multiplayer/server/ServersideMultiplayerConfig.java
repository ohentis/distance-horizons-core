package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.multiplayer.config.AbstractMultiplayerConfig;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfig;
import io.netty.buffer.ByteBuf;

/**
 * Used for constraining the client config to the server config.
 */
public class ServersideMultiplayerConfig extends AbstractMultiplayerConfig
{
	public MultiplayerConfig clientConfig = new MultiplayerConfig();
	
	@Override
	public int getRenderDistance()
	{
		return Math.min(clientConfig.renderDistance, Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get());
	}
	
	@Override
	public int getFullDataRequestRateLimit()
	{
		return Math.min(clientConfig.fullDataRequestRateLimit, Config.Client.Advanced.Multiplayer.ServerNetworking.requestRateLimit.get());
	}
	
	@Override
	public boolean isRealTimeUpdatesEnabled()
	{
		return clientConfig.realTimeUpdatesEnabled && Config.Client.Advanced.Multiplayer.ServerNetworking.enableRealTimeUpdates.get();
	}
	
	@Override
	public boolean isPostRelogUpdateEnabled() {
		return clientConfig.postRelogUpdateEnabled && Config.Client.Advanced.Multiplayer.ServerNetworking.enablePostRelogUpdate.get();
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		throw new UnsupportedOperationException("Decoding is not supported for server-only class.");
	}
	
}
