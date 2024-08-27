package com.seibel.distanthorizons.core.network.event.internal;

/**
 * This event is received instead of a message if its protocol version is incompatible with version the mod uses.
 */
public class IncompatibleMessageEvent extends InternalEvent
{
	public final int protocolVersion;
	
	public IncompatibleMessageEvent(int protocolVersion)
	{
		this.protocolVersion = protocolVersion;
	}
	
}