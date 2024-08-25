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
	
	public int generationRequestRateLimit = Config.Client.Advanced.Multiplayer.ServerNetworking.generationRequestRateLimit.get();
	@Override public int getGenerationRequestRateLimit() { return this.generationRequestRateLimit; }
	
	public boolean realTimeUpdatesEnabled = Config.Client.Advanced.Multiplayer.ServerNetworking.enableRealTimeUpdates.get();
	@Override public boolean isRealTimeUpdatesEnabled() { return this.realTimeUpdatesEnabled; }
	
	public boolean synchronizeOnLogin = Config.Client.Advanced.Multiplayer.ServerNetworking.synchronizeOnLogin.get();
	@Override public boolean getSynchronizeOnLogin() { return this.synchronizeOnLogin; }
	
	public int syncOnLoginRateLimit = Config.Client.Advanced.Multiplayer.ServerNetworking.syncOnLoginRateLimit.get();
	@Override public int getSyncOnLoginRateLimit() { return this.syncOnLoginRateLimit; }
	
	
	@Override
	public void decode(ByteBuf in)
	{
		this.renderDistanceRadius = in.readInt();
		this.distantGenerationEnabled = in.readBoolean();
		this.generationRequestRateLimit = in.readInt();
		this.realTimeUpdatesEnabled = in.readBoolean();
		this.synchronizeOnLogin = in.readBoolean();
		this.syncOnLoginRateLimit = in.readInt();
	}
	
}