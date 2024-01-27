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
	public abstract boolean isPostRelogUpdateEnabled();
	public abstract int getPostRelogUpdateConcurrencyLimit();
	
	@Override
	public void encode(ByteBuf out)
	{
		out.writeInt(this.getRenderDistanceRadius());
		out.writeBoolean(this.isDistantGenerationEnabled());
		out.writeInt(this.getFullDataRequestConcurrencyLimit());
		out.writeInt(this.getGenTaskPriorityRequestRateLimit());
		out.writeBoolean(this.isRealTimeUpdatesEnabled());
		out.writeBoolean(this.isPostRelogUpdateEnabled());
		out.writeInt(this.getPostRelogUpdateConcurrencyLimit());
	}
	
}
