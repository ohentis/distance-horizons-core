package com.seibel.distanthorizons.core.multiplayer.config;

import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import io.netty.buffer.ByteBuf;

public abstract class AbstractMultiplayerConfig implements INetworkObject
{
	public abstract int getRenderDistanceRadius();
	public abstract boolean isDistantGenerationEnabled();
	public abstract int getFullDataRequestConcurrencyLimit();
	public abstract int getGenTaskPriorityRequestRateLimit();
	public abstract boolean isRealTimeUpdatesEnabled();
	public abstract boolean isLoginDataSyncEnabled();
	public abstract int getLoginDataSyncRCLimit();
	public abstract boolean getGenerateMultipleDimensions();
	
	@Override
	public void encode(ByteBuf out)
	{
		out.writeInt(this.getRenderDistanceRadius());
		out.writeBoolean(this.isDistantGenerationEnabled());
		out.writeInt(this.getFullDataRequestConcurrencyLimit());
		out.writeInt(this.getGenTaskPriorityRequestRateLimit());
		out.writeBoolean(this.isRealTimeUpdatesEnabled());
		out.writeBoolean(this.isLoginDataSyncEnabled());
		out.writeInt(this.getLoginDataSyncRCLimit());
		out.writeBoolean(this.getGenerateMultipleDimensions());
	}
	
}
