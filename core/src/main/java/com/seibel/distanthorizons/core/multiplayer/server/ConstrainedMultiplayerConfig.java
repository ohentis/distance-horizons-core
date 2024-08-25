package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.multiplayer.config.AbstractMultiplayerConfig;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfig;
import io.netty.buffer.ByteBuf;

/**
 * Used for constraining the client config to the server config.
 */
public class ConstrainedMultiplayerConfig extends AbstractMultiplayerConfig
{
	public MultiplayerConfig clientConfig = new MultiplayerConfig();
	
	@Override
	public int getRenderDistanceRadius()
	{
		return Math.min(this.clientConfig.renderDistanceRadius, Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get());
	}
	
	@Override
	public boolean isDistantGenerationEnabled()
	{
		return this.clientConfig.distantGenerationEnabled && Config.Client.Advanced.WorldGenerator.enableDistantGeneration.get();
	}
	
	@Override
	public int getGenerationRequestRateLimit()
	{
		return Math.min(this.clientConfig.generationRequestRateLimit, Config.Client.Advanced.Multiplayer.ServerNetworking.generationRequestRateLimit.get());
	}
	
	@Override
	public boolean isRealTimeUpdatesEnabled()
	{
		return this.clientConfig.realTimeUpdatesEnabled && Config.Client.Advanced.Multiplayer.ServerNetworking.enableRealTimeUpdates.get();
	}
	
	@Override
	public boolean getSynchronizeOnLogin()
	{
		return this.clientConfig.synchronizeOnLogin && Config.Client.Advanced.Multiplayer.ServerNetworking.synchronizeOnLogin.get();
	}
	
	@Override
	public int getSyncOnLoginRateLimit()
	{
		return Math.min(this.clientConfig.syncOnLoginRateLimit, Config.Client.Advanced.Multiplayer.ServerNetworking.syncOnLoginRateLimit.get());
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		throw new UnsupportedOperationException("Decoding is not supported for server-only class.");
	}
	
}