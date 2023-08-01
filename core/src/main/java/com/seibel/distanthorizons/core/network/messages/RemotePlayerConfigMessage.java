package com.seibel.distanthorizons.core.network.messages;

import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import com.seibel.distanthorizons.core.multiplayer.RemotePlayer;
import io.netty.buffer.ByteBuf;

public class RemotePlayerConfigMessage extends FutureTrackableNetworkMessage
{
    public RemotePlayer.Payload payload;
	
	
	
    public RemotePlayerConfigMessage() { }
    public RemotePlayerConfigMessage(RemotePlayer.Payload payload) { this.payload = payload; }
	
    @Override
    public void encode0(ByteBuf out) { this.payload.encode(out); }
	
    @Override
    public void decode0(ByteBuf in) { this.payload = INetworkObject.decode(new RemotePlayer.Payload(), in); }
	
}
