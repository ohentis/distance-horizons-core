package com.seibel.distanthorizons.core.network.session;

import java.io.IOException;

public class SessionClosedException extends IOException
{
    public SessionClosedException(String message)
    {
        super(message);
    }
}