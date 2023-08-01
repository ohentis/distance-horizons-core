package com.seibel.distanthorizons.core.multiplayer;

import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.concurrent.atomic.AtomicInteger;

public class RemotePlayer 
{
    public IServerPlayerWrapper serverPlayer;
    public Payload payload;
    public ChannelHandlerContext channelContext;
    public final AtomicInteger pendingFullDataRequests = new AtomicInteger();
	
	
	
    public RemotePlayer(IServerPlayerWrapper serverPlayer) { this.serverPlayer = serverPlayer; }
	
    public static class Payload implements INetworkObject
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
		
    }
	
}
