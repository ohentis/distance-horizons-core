package com.seibel.distanthorizons.core.network.protocol;

import io.netty.buffer.ByteBuf;

public abstract class FutureTrackableNetworkMessage implements INetworkMessage
{
	private static int lastId = 0;
	public int futureId = lastId++;
	
	public static FutureTrackableNetworkMessage makeResponse(FutureTrackableNetworkMessage requestMessage, FutureTrackableNetworkMessage responseMessage)
	{
		responseMessage.futureId = requestMessage.futureId;
		return responseMessage;
	}
	
	@Override public final void encode(ByteBuf out)
	{
		out.writeInt(futureId);
		this.encode0(out);
	}
	
	@Override public final void decode(ByteBuf in)
	{
		futureId = in.readInt();
		this.decode0(in);
	}
	
	protected abstract void encode0(ByteBuf out);
	protected abstract void decode0(ByteBuf in);
}
