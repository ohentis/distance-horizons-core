package com.seibel.distanthorizons.core.util;

import com.seibel.distanthorizons.core.util.objects.UncheckedInterruptedException;

import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

public class ExceptionUtil
{
	public static boolean isShutdownException(Throwable throwable)
	{
		Throwable unwrappedCompletion = throwable;
		while (unwrappedCompletion instanceof CompletionException)
		{
			unwrappedCompletion = unwrappedCompletion.getCause();
		}
		
		return isThrowableShutdown(throwable)
				|| isThrowableShutdown(unwrappedCompletion);
	}
	private static boolean isThrowableShutdown(Throwable throwable)
	{
		return throwable instanceof InterruptedException
				|| throwable instanceof UncheckedInterruptedException
				|| throwable instanceof RejectedExecutionException
				|| throwable instanceof ClosedByInterruptException;
	}
	
	
}
