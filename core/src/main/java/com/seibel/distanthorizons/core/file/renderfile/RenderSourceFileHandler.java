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

package com.seibel.distanthorizons.core.file.renderfile;

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.file.fullDatafile.IFullDataSourceProvider;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.util.FileUtil;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RenderSourceFileHandler implements ILodRenderSourceProvider
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private final ThreadPoolExecutor fileHandlerThreadPool;
	private final F3Screen.NestedMessage threadPoolMsg;
	
	protected final ConcurrentHashMap<DhSectionPos, RenderDataMetaFile> loadedMetaFileBySectionPos = new ConcurrentHashMap<>();
	
	private final IDhClientLevel clientLevel;
	private final File saveDir;
	/** This is the lowest (highest numeric) detail level that this {@link RenderSourceFileHandler} is keeping track of. */
	AtomicInteger topDetailLevelRef = new AtomicInteger(DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
	private final IFullDataSourceProvider fullDataSourceProvider;
	
	private final WeakHashMap<CompletableFuture<?>, ETaskType> taskTracker = new WeakHashMap<>();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public RenderSourceFileHandler(IFullDataSourceProvider sourceProvider, IDhClientLevel clientLevel, AbstractSaveStructure saveStructure)
	{
		this.fullDataSourceProvider = sourceProvider;
		this.clientLevel = clientLevel;
		this.saveDir = saveStructure.getRenderCacheFolder(clientLevel.getLevelWrapper());
		if (!this.saveDir.exists() && !this.saveDir.mkdirs())
		{
			LOGGER.warn("Unable to create render data folder, file saving may fail.");
		}
		this.fileHandlerThreadPool = ThreadUtil.makeSingleThreadPool("Render Source File Handler [" + this.clientLevel.getLevelWrapper().getDimensionType().getDimensionName() + "]");
		
		
		this.threadPoolMsg = new F3Screen.NestedMessage(this::f3Log);
	}
	
	
	
	//===============//
	// file handling //
	//===============//
	
	/** This call is thread safe and can be called concurrently from multiple threads. */
	@Override
	public CompletableFuture<ColumnRenderSource> readAsync(DhSectionPos pos)
	{
		// don't continue if the handler has been shut down
		if (this.fileHandlerThreadPool.isTerminated())
		{
			return CompletableFuture.completedFuture(null);
		}
		
		
		
		this.topDetailLevelRef.updateAndGet(oldDetailLevel -> Math.max(oldDetailLevel, pos.getDetailLevel()));
		RenderDataMetaFile metaFile = this.getLoadOrMakeFile(pos);
		if (metaFile == null)
		{
			return CompletableFuture.completedFuture(ColumnRenderSource.createEmptyRenderSource(pos));
		}
		
		CompletableFuture<ColumnRenderSource> getDataSourceFuture = metaFile.getOrLoadCachedDataSourceAsync(this.fileHandlerThreadPool)
				.handle((renderSource, exception) ->
				{
					if (exception != null)
					{
						LOGGER.error("Uncaught error in readAsync for pos: " + pos + ". Error:", exception);
					}
					
					return (renderSource != null) ? renderSource : ColumnRenderSource.createEmptyRenderSource(pos);
				});
		
		synchronized (this.taskTracker)
		{
			this.taskTracker.put(getDataSourceFuture, ETaskType.READ);
		}
		return getDataSourceFuture;
	}
	/** @return null if there was an issue */
	private RenderDataMetaFile getLoadOrMakeFile(DhSectionPos pos)
	{
		RenderDataMetaFile metaFile = this.loadedMetaFileBySectionPos.get(pos);
		if (metaFile != null)
		{
			return metaFile;
		}
		
		
		File fileToLoad = this.computeRenderFilePath(pos);
		if (fileToLoad.exists())
		{
			synchronized (this)
			{
				// A file exists, but isn't loaded yet.
				
				// Double check locking for loading file, as loading file means also loading the metadata, which
				// while not... Very expensive, is still better to avoid multiple threads doing it, and dumping the
				// duplicated work to the trash. Therefore, eating the overhead of 'synchronized' is worth it.
				metaFile = this.loadedMetaFileBySectionPos.get(pos);
				if (metaFile != null)
				{
					return metaFile; // someone else loaded it already.
				}
				
				try
				{
					metaFile = RenderDataMetaFile.createFromExistingFile(this.fullDataSourceProvider, this.clientLevel, fileToLoad);
					this.topDetailLevelRef.updateAndGet(currentTopDetailLevel -> Math.max(currentTopDetailLevel, pos.getDetailLevel()));
					this.loadedMetaFileBySectionPos.put(pos, metaFile);
					return metaFile;
				}
				catch (IOException e)
				{
					LOGGER.error("Failed to read meta data file at " + fileToLoad + ": ", e);
					FileUtil.renameCorruptedFile(fileToLoad);
				}
			}
		}
		
		
		// File does not exist, create it.
		// In this case, since 'creating' a file object doesn't actually do anything heavy on IO yet, we use CAS
		// to avoid overhead of 'synchronized', and eat the mini-overhead of possibly creating duplicate objects.
		try
		{
			metaFile = RenderDataMetaFile.createNewFileForPos(this.fullDataSourceProvider, this.clientLevel, pos, fileToLoad);
		}
		catch (IOException e)
		{
			LOGGER.error("IOException on creating new render data file at "+pos, e);
			return null;
		}
		
		this.topDetailLevelRef.updateAndGet(oldDetailLevel -> Math.max(oldDetailLevel, pos.getDetailLevel()));
		
		// This is a Compare And Swap with expected null value.
		RenderDataMetaFile metaFileCas = this.loadedMetaFileBySectionPos.putIfAbsent(pos, metaFile);
		return (metaFileCas == null) ? metaFile : metaFileCas;
	}
	
	
	
	
	//=============//
	// data saving //
	//=============//
	
	/**
	 * This call is thread safe and can be called concurrently from multiple threads. <br>
	 * This allows fast writes of new data to the render source, without having to wait for the data to be written to disk.
	 */
	@Override
	public void writeChunkDataToFile(DhSectionPos sectionPos, ChunkSizedFullDataAccessor chunkDataView)
	{
		// convert to the lowest detail level so all detail levels are updated
		this.writeChunkDataToFileRecursively(chunkDataView, DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		this.fullDataSourceProvider.writeChunkDataToFile(sectionPos, chunkDataView);
	}
	private void writeChunkDataToFileRecursively(ChunkSizedFullDataAccessor chunk, byte sectionDetailLevel)
	{
		DhSectionPos boundingPos = chunk.getSectionPos();
		DhSectionPos minSectionPos = boundingPos.convertNewToDetailLevel(sectionDetailLevel);
		
		DhSectionPos.DhMutableSectionPos fileSectionPos = new DhSectionPos.DhMutableSectionPos((byte)0, 0, 0);
		
		int width = (sectionDetailLevel > boundingPos.getDetailLevel()) ? 1 : boundingPos.getWidthCountForLowerDetailedSection(sectionDetailLevel);
		for (int xOffset = 0; xOffset < width; xOffset++)
		{
			for (int zOffset = 0; zOffset < width; zOffset++)
			{
				fileSectionPos.mutate(sectionDetailLevel, minSectionPos.getX() + xOffset, minSectionPos.getZ() + zOffset);
				RenderDataMetaFile metaFile = this.loadedMetaFileBySectionPos.get(fileSectionPos); // bypass the getLoadOrMakeFile() since we only want cached files.
				if (metaFile != null)
				{
					metaFile.updateChunkIfSourceExistsAsync(chunk);
				}
			}
		}
		
		if (sectionDetailLevel < this.topDetailLevelRef.get())
		{
			this.writeChunkDataToFileRecursively(chunk, (byte) (sectionDetailLevel + 1));
		}
	}
	
	
	/** This call is thread safe and can be called concurrently from multiple threads. */
	@Override
	public CompletableFuture<Void> flushAndSaveAsync()
	{
		ArrayList<CompletableFuture<Void>> futures = new ArrayList<>();
		for (RenderDataMetaFile metaFile : this.loadedMetaFileBySectionPos.values())
		{
			futures.add(metaFile.flushAndSaveAsync());
		}
		
		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
	}
	
	
	
	//=========//
	// F3 menu //
	//=========//
	
	/** Returns what should be displayed in Minecraft's F3 debug menu */
	private String[] f3Log()
	{
		ArrayList<String> lines = new ArrayList<>();
		lines.add("Render Source File Handler [" + this.clientLevel.getClientLevelWrapper().getDimensionType().getDimensionName() + "]");
		lines.add("  Loaded files: " + this.loadedMetaFileBySectionPos.size());
		lines.add("  Thread pool tasks: " + this.fileHandlerThreadPool.getQueue().size() + " (completed: " + this.fileHandlerThreadPool.getCompletedTaskCount() + ")");
		
		int totalFutures = this.taskTracker.size();
		EnumMap<ETaskType, Integer> tasksOutstanding = new EnumMap<>(ETaskType.class);
		EnumMap<ETaskType, Integer> tasksCompleted = new EnumMap<>(ETaskType.class);
		for (ETaskType type : ETaskType.values())
		{
			tasksOutstanding.put(type, 0);
			tasksCompleted.put(type, 0);
		}
		
		synchronized (this.taskTracker)
		{
			for (Map.Entry<CompletableFuture<?>, ETaskType> entry : this.taskTracker.entrySet())
			{
				if (entry.getKey().isDone())
				{
					tasksCompleted.put(entry.getValue(), tasksCompleted.get(entry.getValue()) + 1);
				}
				else
				{
					tasksOutstanding.put(entry.getValue(), tasksOutstanding.get(entry.getValue()) + 1);
				}
			}
		}
		int totalOutstanding = tasksOutstanding.values().stream().mapToInt(Integer::intValue).sum();
		lines.add("  Futures: " + totalFutures + " (outstanding: " + totalOutstanding + ")");
		for (ETaskType type : ETaskType.values())
		{
			lines.add("    " + type + ": " + tasksOutstanding.get(type) + " / " + (tasksOutstanding.get(type) + tasksCompleted.get(type)));
		}
		return lines.toArray(new String[0]);
	}
	
	
	
	//=====================//
	// clearing / shutdown //
	//=====================//
	
	@Override
	public void close()
	{
		LOGGER.info("Closing " + this.getClass().getSimpleName() + " with [" + this.loadedMetaFileBySectionPos.size() + "] files...");
		this.fileHandlerThreadPool.shutdown();
		this.threadPoolMsg.close();
	}
	
	public void deleteRenderCache()
	{
		// delete each file in the cache directory
		File[] renderFiles = this.saveDir.listFiles();
		if (renderFiles != null)
		{
			for (File renderFile : renderFiles)
			{
				if (!renderFile.delete())
				{
					LOGGER.warn("Unable to delete render file: " + renderFile.getPath());
				}
			}
		}
		
		// clear the cached files
		this.loadedMetaFileBySectionPos.clear();
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	public File computeRenderFilePath(DhSectionPos pos) { return new File(this.saveDir, pos.serialize() + RenderDataMetaFile.FILE_SUFFIX); }
	
	
	
	//================//
	// helper classes //
	//================//
	
	/**
	 * READ <br>
	 * UPDATE_READ_DATA <br>
	 * UPDATE <br>
	 * ON_LOADED <br>
	 */
	private enum ETaskType 
	{
		READ, UPDATE_READ_DATA, UPDATE, ON_LOADED, 
	}
	
}
