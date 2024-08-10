package com.seibel.distanthorizons.core.network.event;

import com.seibel.distanthorizons.core.network.messages.NetworkMessage;
import org.jetbrains.annotations.Nullable;

/**
 * This event is used to indicate that encoding or decoding of a message threw an exception.
 */
public class ProtocolErrorEvent extends InternalEvent
{
	public final Throwable reason;
	@Nullable
	public final NetworkMessage message;
	public final boolean replyWithCloseReason;
	
	public ProtocolErrorEvent(Throwable reason, @Nullable NetworkMessage message, boolean replyWithCloseReason)
	{
		this.reason = reason;
		this.message = message;
		this.replyWithCloseReason = replyWithCloseReason;
	}
	
}