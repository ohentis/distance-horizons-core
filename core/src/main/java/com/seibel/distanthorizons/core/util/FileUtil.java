package com.seibel.distanthorizons.core.util;

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import org.apache.logging.log4j.Logger;

import java.io.File;

public class FileUtil
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	/**
	 * Renames the given file to FILE_NAME.ORIGINAL_PREFIX.corrupted.
	 * If an existing corrupted file already exists, this will attempt to remove it first.
	 *
	 * @return the file after it has been renamed
	 */
	public static File renameCorruptedFile(File file)
	{
		String corruptedFileName = file.getName() + ".corrupted";
		
		File corruptedFile = new File(file.getParentFile(), corruptedFileName);
		if (corruptedFile.exists())
		{
			// could happen if there was a corrupted file before that was removed
			if (!corruptedFile.delete())
			{
				LOGGER.error("Unable to delete pre-existing corrupted file [" + corruptedFileName + "].");
			}
		}
		
		
		if (file.renameTo(corruptedFile))
		{
			LOGGER.error("Renamed corrupted file to [" + corruptedFileName + "].");
		}
		else
		{
			LOGGER.error("Failed to rename corrupted file to [" + corruptedFileName + "]. Attempting to delete file...");
			if (!file.delete())
			{
				LOGGER.error("Unable to delete corrupted file [" + corruptedFileName + "].");
			}
		}
		
		return corruptedFile;
	}
	
	
}
