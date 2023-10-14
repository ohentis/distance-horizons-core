package com.seibel.distanthorizons.coreapi.util.jar;

import java.io.File;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * Deletes the first file in the arguments when a lock is lifted from it (for Windows) <br>
 * DON'T MOVE: If this class is moved, then the updater will no longer work on Windows
 * 
 * @author coolgi 
 */
public class DeleteOnUnlock
{
	/** How long to wait after attempting once (milliseconds) */
	private static final int attemptSpeed = 100;
	/** How many minutes of attempting before it stops */
	private static final int timeout = 60; // Will continue attempting for an hour, if it doesnt unlock by then, then the computer must be way to slow
	
	
	/**
	 * @param args Takes whatever the first argument is, treats it as a file, and deletes it once a process lock is lifted from it <br>
	 *  Note: This argument should also be encoded in UTF-8
	 */
	public static void main(String[] args)
	{
		try
		{
			File file = new File(
					URLDecoder.decode(args[0], "UTF-8")
			);
			
			
			for (int i = 0; i < (60 / ((float) attemptSpeed/1000) ) * timeout; i++)
			{
				if (file.renameTo(file)) // If it is able to be renamed, then it is unlocked and can be deleted
				{
					Files.delete(file.toPath());
					break;
				}
				TimeUnit.MILLISECONDS.sleep(attemptSpeed);
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
			throw new RuntimeException("Deletion failed");
		}
	}
}
