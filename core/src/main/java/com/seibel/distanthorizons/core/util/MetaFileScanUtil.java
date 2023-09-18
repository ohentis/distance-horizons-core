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

import com.google.common.collect.HashMultimap;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataFileHandler;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataMetaFile;
import com.seibel.distanthorizons.core.file.fullDatafile.IFullDataSourceProvider;
import com.seibel.distanthorizons.core.file.metaData.AbstractMetaDataContainerFile;
import com.seibel.distanthorizons.core.file.renderfile.ILodRenderSourceProvider;
import com.seibel.distanthorizons.core.file.renderfile.RenderDataMetaFile;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Used to pull in the initial files used by both {@link IFullDataSourceProvider} and {@link ILodRenderSourceProvider}s. */
public class MetaFileScanUtil
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	public static final int MAX_SCAN_DEPTH = 5;
	
	
	
	//===============//
	// file scanning //
	//===============//
	
	// file scanning means to find all File's in a given directory
	
	// TODO merge with the below method
	public static void scanFullDataFiles(AbstractSaveStructure saveStructure, ILevelWrapper levelWrapper, IFullDataSourceProvider dataSourceProvider)
	{
		try (Stream<Path> pathStream = Files.walk(saveStructure.getFullDataFolder(levelWrapper).toPath(), MAX_SCAN_DEPTH))
		{
			List<File> files = pathStream.filter(
					path -> path.toFile().getName().endsWith(FullDataMetaFile.FILE_SUFFIX) && path.toFile().isFile()
			).map(Path::toFile).collect(Collectors.toList());
			LOGGER.info("Found " + files.size() + " full data files for " + levelWrapper + " in " + saveStructure);
			dataSourceProvider.addScannedFiles(files);
		}
		catch (Exception e)
		{
			LOGGER.error("Failed to scan and collect full data files for " + levelWrapper + " in " + saveStructure, e);
		}
	}
	
	// TODO merge with the above method
	public static void scanRenderFiles(AbstractSaveStructure saveStructure, ILevelWrapper levelWrapper, ILodRenderSourceProvider renderSourceProvider)
	{
		try (Stream<Path> pathStream = Files.walk(saveStructure.getRenderCacheFolder(levelWrapper).toPath(), MAX_SCAN_DEPTH))
		{
			List<File> files = pathStream.filter(
					path -> path.toFile().getName().endsWith(RenderDataMetaFile.FILE_SUFFIX) && path.toFile().isFile()
			).map(Path::toFile).collect(Collectors.toList());
			LOGGER.info("Found " + files.size() + " render cache files for " + levelWrapper + " in " + saveStructure);
			renderSourceProvider.addScannedFiles(files);
		}
		catch (Exception e)
		{
			LOGGER.error("Failed to scan and collect cache files for " + levelWrapper + " in " + saveStructure, e);
		}
	}
	
	
	
	//======================//
	// Adding scanned files //
	//======================//
	
	/**
	 * Caller must ensure that this method is called only once,
	 * and that the {@link FullDataFileHandler} is not used before this method is called.
	 */
	public static void addScannedFiles(
			Collection<File> detectedFiles, boolean useLazyLoading, String fileSuffix,
			ICreateMetadataFunc createMetadataFunc,
			IAddUnloadedFileFunc addUnloadedFileFunc, IAddLoadedMetaFileFunc addLoadedMetaFileFunc)
	{
		if (useLazyLoading)
		{
			lazyAddScannedFile(detectedFiles, fileSuffix, addUnloadedFileFunc);
		}
		else
		{
			immediateAddScannedFile(detectedFiles, createMetadataFunc, addLoadedMetaFileFunc);
		}
	}
	private static void lazyAddScannedFile(Collection<File> detectedFiles, String fileSuffix, IAddUnloadedFileFunc addUnloadedFileFunc)
	{
		for (File file : detectedFiles)
		{
			if (file == null || !file.exists())
			{
				// can rarely happen if the user rapidly travels between dimensions
				LOGGER.warn("Null or non-existent file: " + ((file != null) ? file.getPath() : "NULL"));
				continue;
			}
			
			try
			{
				DhSectionPos pos = decodePositionFromFileName(file, fileSuffix);
				if (pos != null)
				{
					addUnloadedFileFunc.addFile(pos, file);
				}
			}
			catch (Exception e)
			{
				LOGGER.error("Failed to read data meta file at " + file + ": ", e);
				FileUtil.renameCorruptedFile(file);
			}
		}
	}
	private static void immediateAddScannedFile(
			Collection<File> detectedFiles, 
			ICreateMetadataFunc createMetadataFunc, IAddLoadedMetaFileFunc addLoadedMetaFileFunc)
	{
		HashMultimap<DhSectionPos, AbstractMetaDataContainerFile> filesByPos = HashMultimap.create();
		{ // Sort files by pos.
			for (File file : detectedFiles)
			{
				try
				{
					AbstractMetaDataContainerFile metaFile = createMetadataFunc.createFile(file);
					filesByPos.put(metaFile.pos, metaFile);
				}
				catch (IOException e)
				{
					LOGGER.error("Failed to read data meta file at " + file + ": ", e);
					FileUtil.renameCorruptedFile(file);
				}
			}
		}
		
		
		// Warn for multiple files with the same pos, and then select the one with the latest timestamp.
		for (DhSectionPos pos : filesByPos.keySet())
		{
			Collection<AbstractMetaDataContainerFile> metaFiles = filesByPos.get(pos);
			AbstractMetaDataContainerFile metaFileToUse;
			if (metaFiles.size() > 1)
			{
				// sort by the file's last modified date
				metaFileToUse = Collections.max(metaFiles, Comparator.comparingLong(fullDataMetaFile -> fullDataMetaFile.file.lastModified()));
				
				// log the duplicate files
				StringBuilder duplicateMessage = new StringBuilder();
				duplicateMessage.append("Multiple files with the same pos: ").append(pos).append("\n");
				for (AbstractMetaDataContainerFile metaFile : metaFiles)
				{
					duplicateMessage.append("\t").append(metaFile.file).append("\n");
				}
				duplicateMessage.append("\tUsing: ").append(metaFileToUse.file).append("\n");
				duplicateMessage.append("(Other files will be renamed by appending \".old\" to their name.)");
				LOGGER.warn(duplicateMessage.toString());
				
				
				
				// Rename all other files with the same pos to .old
				for (AbstractMetaDataContainerFile metaFile : metaFiles)
				{
					if (metaFile == metaFileToUse)
					{
						continue;
					}
					
					
					File oldFile = new File(metaFile.file + ".old");
					try
					{
						if (!metaFile.file.renameTo(oldFile))
						{
							throw new RuntimeException("Renaming failed");
						}
					}
					catch (Exception e)
					{
						LOGGER.error("Failed to rename file: " + metaFile.file + " to " + oldFile, e);
					}
				}
			}
			else
			{
				metaFileToUse = metaFiles.iterator().next();
			}
			
			// Add file to the list of files.
			addLoadedMetaFileFunc.addFile(pos, metaFileToUse);
		}
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	/** @return null if the file name can't be parsed into a {@link DhSectionPos} */
	@Nullable
	public static DhSectionPos decodePositionFromFileName(File file, String fileSuffix)
	{
		String fileName = file.getName();
		if (!fileName.endsWith(fileSuffix))
		{
			return null;
		}
		
		fileName = fileName.substring(0, fileName.length() - 4);
		return DhSectionPos.deserialize(fileName);
	}
	
	
	
	//===================//
	// helper interfaces //
	//===================//
	
	@FunctionalInterface
	public interface ICreateMetadataFunc { AbstractMetaDataContainerFile createFile(File file) throws IOException; }
	
	@FunctionalInterface
	public interface IAddUnloadedFileFunc { void addFile(DhSectionPos pos, File file); }
	@FunctionalInterface
	public interface IAddLoadedMetaFileFunc { void addFile(DhSectionPos pos, AbstractMetaDataContainerFile metaFile); }
	
}
