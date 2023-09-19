package com.seibel.distanthorizons.core.multiplayer;

import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import io.netty.buffer.ByteBuf;

public class MultiplayerConfig implements INetworkObject
{
	public int renderDistance;
	public int fullDataRequestRateLimit;
	
	
	@Override
	public void encode(ByteBuf out)
	{
		out.writeInt(this.renderDistance);
		out.writeInt(this.fullDataRequestRateLimit);
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		this.renderDistance = in.readInt();
		this.fullDataRequestRateLimit = in.readInt();
	}
	
	@Override public String toString()
	{
		return "MultiplayerConfig{" +
				"renderDistance=" + renderDistance +
				", fullDataRequestRateLimit=" + fullDataRequestRateLimit +
				'}';
	}
	
}
