package com.seibel.distanthorizons.core.multiplayer.config;

import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import io.netty.buffer.ByteBuf;

public abstract class AbstractMultiplayerConfig implements INetworkObject
{
	public abstract int getRenderDistanceRadius();
	public abstract int getFullDataRequestRateLimit();
	public abstract boolean isRealTimeUpdatesEnabled();
	public abstract boolean isPostRelogUpdateEnabled();
	
	@Override
	public void encode(ByteBuf out)
	{
		out.writeInt(this.getRenderDistanceRadius());
		out.writeInt(this.getFullDataRequestRateLimit());
		out.writeBoolean(this.isRealTimeUpdatesEnabled());
		out.writeBoolean(this.isPostRelogUpdateEnabled());
	}
	
}
