package com.seibel.distanthorizons.core.network.messages;

import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

public class PlayerUUIDMessage extends FutureTrackableNetworkMessage
{
	public UUID playerUUID;

	public PlayerUUIDMessage() { }
	public PlayerUUIDMessage(UUID playerUUID) { this.playerUUID = playerUUID; }
	
	@Override
	public void encode0(ByteBuf out)
	{
		out.writeLong(this.playerUUID.getMostSignificantBits());
		out.writeLong(this.playerUUID.getLeastSignificantBits());
	}
	
	@Override
	public void decode0(ByteBuf in) { this.playerUUID = new UUID(in.readLong(), in.readLong()); }
	
}
