package com.seibel.distanthorizons.core.network.messages;

import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

public class ExceptionMessage extends FutureTrackableNetworkMessage
{
	private static final List<Class<? extends Exception>> exceptionMap = new ArrayList<Class<? extends Exception>>()
	{{
		// All exceptions here must include constructor: (String)
		add(RateLimitedException.class);
	}};
	
	public Exception exception;
	
	public ExceptionMessage() { }
	public ExceptionMessage(Exception exception)
	{
		this.exception = exception;
	}
	
	@Override protected void encode0(ByteBuf out)
	{
		out.writeInt(exceptionMap.indexOf(exception.getClass()));
		encodeString(exception.getMessage(), out);
	}
	
	@Override protected void decode0(ByteBuf in) throws Exception
	{
		int id = in.readInt();
		String message = decodeString(in);
		exception = exceptionMap.get(id).getDeclaredConstructor(String.class).newInstance(message);
	}
}
