package com.seibel.distanthorizons.core.multiplayer.config;

import com.google.common.base.MoreObjects;
import com.seibel.distanthorizons.core.network.INetworkObject;
import io.netty.buffer.ByteBuf;

public abstract class AbstractMultiplayerConfig implements INetworkObject
{
	public abstract int getRenderDistanceRadius();
	public abstract boolean isDistantGenerationEnabled();
	public abstract int getGenerationRequestRateLimit();
	public abstract boolean isRealTimeUpdatesEnabled();
	public abstract boolean getSynchronizeOnLogin();
	public abstract int getSyncOnLoginRateLimit();
	
	@Override
	public void encode(ByteBuf out)
	{
		out.writeInt(this.getRenderDistanceRadius());
		out.writeBoolean(this.isDistantGenerationEnabled());
		out.writeInt(this.getGenerationRequestRateLimit());
		out.writeBoolean(this.isRealTimeUpdatesEnabled());
		out.writeBoolean(this.getSynchronizeOnLogin());
		out.writeInt(this.getSyncOnLoginRateLimit());
	}
	
	
	@Override
	public String toString()
	{
		return MoreObjects.toStringHelper(this)
				.add("renderDistanceRadius", this.getRenderDistanceRadius())
				.add("distantGenerationEnabled", this.isDistantGenerationEnabled())
				.add("generationRequestRateLimit", this.getGenerationRequestRateLimit())
				.add("realTimeUpdatesEnabled", this.isRealTimeUpdatesEnabled())
				.add("synchronizeOnLogin", this.getSynchronizeOnLogin())
				.add("syncOnLoginRateLimit", this.getSyncOnLoginRateLimit())
				.toString();
	}
	
}