package com.seibel.distanthorizons.core.network.session;

import java.io.IOException;

/** The exception thrown if DH's networking networkSession has been shut down. */
public class SessionClosedException extends IOException
{
    public SessionClosedException(String message) { super(message); }
	
}