package com.seibel.distanthorizons.core.network.event;

import com.seibel.distanthorizons.core.network.messages.NetworkMessage;
import org.jetbrains.annotations.Nullable;

/**
 * This event is used to indicate that encoding or decoding of a message threw an exception.
 */
public class ProtocolErrorEvent extends InternalEvent
{
	public final Throwable throwable;
	@Nullable
	public final NetworkMessage message;
	
	public ProtocolErrorEvent(Throwable throwable, @Nullable NetworkMessage message)
	{
		this.throwable = throwable;
		this.message = message;
	}
	
}