package com.seibel.distanthorizons.core.network.exceptions;

public class RateLimitedException extends Exception
{
	public RateLimitedException(String message)
	{
		super(message);
	}
}
