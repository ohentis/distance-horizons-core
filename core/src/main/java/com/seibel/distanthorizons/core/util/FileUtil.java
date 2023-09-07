/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2023 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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
