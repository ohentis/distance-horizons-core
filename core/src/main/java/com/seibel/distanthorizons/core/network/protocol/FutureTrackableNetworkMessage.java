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
		try
		{
			out.writeInt(futureId);
			this.encode0(out);
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}
	
	@Override public final void decode(ByteBuf in)
	{
		try
		{
			futureId = in.readInt();
			this.decode0(in);
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}
	
	protected abstract void encode0(ByteBuf out) throws Exception;
	protected abstract void decode0(ByteBuf in) throws Exception;
}
