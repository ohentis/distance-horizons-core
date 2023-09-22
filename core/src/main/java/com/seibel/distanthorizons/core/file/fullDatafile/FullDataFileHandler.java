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

package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.HighDetailIncompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.LowDetailIncompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IIncompleteFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.util.MetaFileScanUtil;
import com.seibel.distanthorizons.core.util.FileUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class FullDataFileHandler implements IFullDataSourceProvider
{
	public static final boolean USE_LAZY_LOADING = true;
	
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	protected static ExecutorService fileHandlerThreadPool;
	protected static ConfigChangeListener<Integer> configListener;
	
	private final ConcurrentHashMap<DhSectionPos, File> unloadedFileBySectionPos = new ConcurrentHashMap<>();
	/** contains the loaded {@link FullDataMetaFile}'s */
	private final ConcurrentHashMap<DhSectionPos, FullDataMetaFile> metaFileBySectionPos = new ConcurrentHashMap<>();
	
	public Map<DhSectionPos, Integer> getLoadStates(Iterable<DhSectionPos> posList)
	{
		HashMap<DhSectionPos, Integer> map = new HashMap<>();
		for (DhSectionPos pos : posList)
		{
			map.put(pos,
					metaFileBySectionPos.containsKey(pos) ? 3 // Loaded
					: this.isFileUnloaded(pos) ? 2            // Unloaded
					: 1);                                     // Not generated
		}
		return map;
	}
	protected boolean isFileUnloaded(DhSectionPos pos) { return unloadedFileBySectionPos.containsKey(pos); }
	
	protected final IDhLevel level;
	protected final File saveDir;
	protected final AtomicInteger topDetailLevelRef = new AtomicInteger(0);
	protected final int minDetailLevel = CompleteFullDataSource.SECTION_SIZE_OFFSET;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public FullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure)
	{
		this.level = level;
		this.saveDir = saveStructure.getFullDataFolder(level.getLevelWrapper());
		if (!this.saveDir.exists() && !this.saveDir.mkdirs())
		{
			LOGGER.warn("Unable to create full data folder, file saving may fail.");
		}
		MetaFileScanUtil.scanFullDataFiles(saveStructure, level.getLevelWrapper(), this);
	}
	
	@Override
	public void addScannedFiles(Collection<File> detectedFiles)
	{
		MetaFileScanUtil.ICreateMetadataFunc createMetadataFunc = (file) -> FullDataMetaFile.createFromExistingFile(this, this.level, file);
		
		MetaFileScanUtil.IAddUnloadedFileFunc addUnloadedFileFunc = (pos, file) ->
		{
			this.unloadedFileBySectionPos.put(pos, file);
			this.topDetailLevelRef.updateAndGet(oldDetailLevel -> Math.max(oldDetailLevel, pos.getDetailLevel()));
		};
		MetaFileScanUtil.IAddLoadedMetaFileFunc addLoadedMetaFileFunc = (pos, loadedMetaFile) ->
		{
			this.topDetailLevelRef.updateAndGet(oldDetailLevel -> Math.max(oldDetailLevel, pos.getDetailLevel()));
			this.metaFileBySectionPos.put(pos, (FullDataMetaFile) loadedMetaFile);
		};
		
		
		MetaFileScanUtil.addScannedFiles(detectedFiles, USE_LAZY_LOADING, FullDataMetaFile.FILE_SUFFIX,
				createMetadataFunc,
				addUnloadedFileFunc, addLoadedMetaFileFunc);
	}
	
	
	
	//===============//
	// file handling //
	//===============//
	
	/**
	 * Returns the {@link IFullDataSource} for the given section position. <Br>
	 * The returned data source may be null. <Br> <Br>
	 *
	 * For now, if result is null, it prob means error has occurred when loading or creating the file object. <Br> <Br>
	 *
	 * This call is concurrent. I.e. it supports being called by multiple threads at the same time.
	 */
	@Override
	public CompletableFuture<IFullDataSource> readAsync(DhSectionPos pos)
	{
		this.topDetailLevelRef.updateAndGet(oldDetailLevel -> Math.max(oldDetailLevel, pos.getDetailLevel()));
		FullDataMetaFile metaFile = this.getLoadOrMakeFile(pos, true);
		if (metaFile == null)
		{
			return CompletableFuture.completedFuture(null);
		}
		
		
		// future wrapper necessary in order to handle file read errors
		CompletableFuture<IFullDataSource> futureWrapper = new CompletableFuture<>();
		metaFile.getOrLoadCachedDataSourceAsync().exceptionally((e) ->
				{
					FullDataMetaFile newMetaFile = this.removeCorruptedFile(pos, metaFile, e);
					
					futureWrapper.completeExceptionally(e);
					return null; // return value doesn't matter
				})
				.whenComplete((dataSource, e) ->
				{
					futureWrapper.complete(dataSource);
				});
		
		return futureWrapper;
	}
	
	@Override
	public FullDataMetaFile getFileIfExist(DhSectionPos pos) { return this.getLoadOrMakeFile(pos, false); }
	protected FullDataMetaFile getLoadOrMakeFile(DhSectionPos pos, boolean allowCreateFile)
	{
		FullDataMetaFile metaFile = this.metaFileBySectionPos.get(pos);
		if (metaFile != null)
		{
			return metaFile;
		}
		
		
		File fileToLoad = this.unloadedFileBySectionPos.get(pos);
		// File does exist, but not loaded yet.
		if (fileToLoad != null)
		{
			synchronized (this)
			{
				// Double check locking for loading file, as loading file means also loading the metadata, which
				// while not... Very expensive, is still better to avoid multiple threads doing it, and dumping the
				// duplicated work to the trash. Therefore, eating the overhead of 'synchronized' is worth it.
				metaFile = this.metaFileBySectionPos.get(pos);
				if (metaFile != null)
				{
					return metaFile; // someone else loaded it already.
				}
				
				try
				{
					metaFile = FullDataMetaFile.createFromExistingFile(this, this.level, fileToLoad);
					this.topDetailLevelRef.updateAndGet(oldDetailLevel -> Math.max(oldDetailLevel, pos.getDetailLevel()));
					this.metaFileBySectionPos.put(pos, metaFile);
					return metaFile;
				}
				catch (IOException e)
				{
					LOGGER.error("Failed to read data meta file at " + fileToLoad + ": ", e);
					FileUtil.renameCorruptedFile(fileToLoad);
				}
				finally
				{
					this.unloadedFileBySectionPos.remove(pos);
				}
			}
		}
		
		
		if (!allowCreateFile)
		{
			return null;
		}
		
		// File does not exist, create it.
		// In this case, since 'creating' a file object doesn't actually do anything heavy on IO yet, we use CAS
		// to avoid overhead of 'synchronized', and eat the mini-overhead of possibly creating duplicate objects.
		try
		{
			metaFile = FullDataMetaFile.createNewFileForPos(this, this.level, pos);
		}
		catch (IOException e)
		{
			LOGGER.error("IOException on creating new data file at {}", pos, e);
			return null;
		}
		
		this.topDetailLevelRef.updateAndGet(oldDetailLevel -> Math.max(oldDetailLevel, pos.getDetailLevel()));
		
		// This is a CAS with expected null value.
		FullDataMetaFile metaFileCas = this.metaFileBySectionPos.putIfAbsent(pos, metaFile);
		return metaFileCas == null ? metaFile : metaFileCas;
	}
	
	/**
	 * Populates the preexistingFiles and missingFilePositions ArrayLists.
	 *
	 * @param preexistingFiles the list of {@link FullDataMetaFile}'s that have been created for the given position.
	 * @param missingFilePositions the list of {@link DhSectionPos}'s that don't have {@link FullDataMetaFile} created for them yet.
	 */
	protected void getDataFilesForPosition(
			DhSectionPos effectivePos, DhSectionPos posAreaToGet,
			ArrayList<FullDataMetaFile> preexistingFiles, ArrayList<DhSectionPos> missingFilePositions)
	{
		byte sectionDetail = posAreaToGet.getDetailLevel();
		boolean allEmpty = true;
		
		final DhSectionPos.DhMutableSectionPos subPos = new DhSectionPos.DhMutableSectionPos((byte)0, 0, 0);
		
		// get all existing files for this position
		outerLoop:
		while (--sectionDetail >= this.minDetailLevel)
		{
			DhLodPos minPos = posAreaToGet.getMinCornerLodPos().getCornerLodPos(sectionDetail);
			int count = posAreaToGet.getSectionBBoxPos().getWidthAtDetail(sectionDetail);
			
			for (int xOffset = 0; xOffset < count; xOffset++)
			{
				for (int zOffset = 0; zOffset < count; zOffset++)
				{
					subPos.mutate(sectionDetail, xOffset + minPos.x, zOffset + minPos.z);
					LodUtil.assertTrue(posAreaToGet.overlapsExactly(effectivePos) && subPos.overlapsExactly(posAreaToGet));
					
					//TODO: The following check is temporary as we only sample corner points, which means
					// on a very different level, we may not need the entire section at all.
					if (!CompleteFullDataSource.firstDataPosCanAffectSecond(effectivePos, subPos))
					{
						continue;
					}
					
					// check if a file for this pos exists, either loaded and unloaded
					if (this.metaFileBySectionPos.containsKey(subPos) || this.unloadedFileBySectionPos.containsKey(subPos))
					{
						allEmpty = false;
						break outerLoop;
					}
				}
			}
		}
		
		if (allEmpty)
		{
			// there are no children to this quad tree,
			// add this leaf's position
			missingFilePositions.add(posAreaToGet);
		}
		else
		{
			// there are children in this quad tree, search them
			this.recursiveGetDataFilesForPosition(0, effectivePos, posAreaToGet, preexistingFiles, missingFilePositions);
			this.recursiveGetDataFilesForPosition(1, effectivePos, posAreaToGet, preexistingFiles, missingFilePositions);
			this.recursiveGetDataFilesForPosition(2, effectivePos, posAreaToGet, preexistingFiles, missingFilePositions);
			this.recursiveGetDataFilesForPosition(3, effectivePos, posAreaToGet, preexistingFiles, missingFilePositions);
		}
	}
	private void recursiveGetDataFilesForPosition(int childIndex, DhSectionPos basePos, DhSectionPos pos, ArrayList<FullDataMetaFile> preexistingFiles, ArrayList<DhSectionPos> missingFilePositions)
	{
		DhSectionPos childPos = pos.getChildByIndex(childIndex);
		if (CompleteFullDataSource.firstDataPosCanAffectSecond(basePos, childPos))
		{
			// load the file if it isn't already
			if (this.unloadedFileBySectionPos.containsKey(childPos))
			{
				this.getLoadOrMakeFile(childPos, true);
			}
			
			
			FullDataMetaFile metaFile = this.metaFileBySectionPos.get(childPos);
			if (metaFile != null)
			{
				// we have reached a populated leaf node in the quad tree
				preexistingFiles.add(metaFile);
			}
			else if (childPos.getDetailLevel() == this.minDetailLevel)
			{
				// we have reached an empty leaf node in the quad tree
				missingFilePositions.add(childPos);
			}
			else
			{
				// recursively traverse down the tree
				this.getDataFilesForPosition(basePos, childPos, preexistingFiles, missingFilePositions);
			}
		}
	}
	
	public void ForEachFile(Consumer<FullDataMetaFile> consumer) { this.metaFileBySectionPos.values().forEach(consumer); }
	
	
		
	
	//=============//
	// data saving //
	//=============//
	
	/** This call is concurrent. I.e. it supports being called by multiple threads at the same time. */
	@Override
	public void writeChunkDataToFile(DhSectionPos sectionPos, ChunkSizedFullDataAccessor chunkDataView)
	{
		DhSectionPos chunkSectionPos = chunkDataView.getSectionPos();
		LodUtil.assertTrue(chunkSectionPos.overlapsExactly(sectionPos), "Chunk " + chunkSectionPos + " does not overlap section " + sectionPos);
		
		chunkSectionPos = chunkSectionPos.convertNewToDetailLevel((byte) this.minDetailLevel);
		this.writeChunkDataToMetaFile(chunkSectionPos, chunkDataView);
	}
	private void writeChunkDataToMetaFile(DhSectionPos sectionPos, ChunkSizedFullDataAccessor chunkData)
	{
		FullDataMetaFile metaFile = this.metaFileBySectionPos.get(sectionPos);
		if (metaFile != null)
		{
			// there is a file for this position
			metaFile.addToWriteQueue(chunkData);
		}
		
		if (sectionPos.getDetailLevel() <= this.topDetailLevelRef.get())
		{
			// recursively attempt to get the meta file for this position
			this.writeChunkDataToMetaFile(sectionPos.getParentPos(), chunkData);
		}
	}
	
	/** This call is concurrent. I.e. it supports multiple threads calling this method at the same time. */
	@Override
	public CompletableFuture<Void> flushAndSave()
	{
		ArrayList<CompletableFuture<Void>> futures = new ArrayList<>();
		for (FullDataMetaFile metaFile : this.metaFileBySectionPos.values())
		{
			futures.add(metaFile.flushAndSaveAsync());
		}
		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
	}
	
	@Override
	public CompletableFuture<Void> flushAndSave(DhSectionPos sectionPos)
	{
		FullDataMetaFile metaFile = this.metaFileBySectionPos.get(sectionPos);
		if (metaFile == null)
		{
			return CompletableFuture.completedFuture(null);
		}
		return metaFile.flushAndSaveAsync();
	}
	
	
	
	
	protected IIncompleteFullDataSource makeEmptyDataSource(DhSectionPos pos)
	{
		return pos.getDetailLevel() <= HighDetailIncompleteFullDataSource.MAX_SECTION_DETAIL ?
				HighDetailIncompleteFullDataSource.createEmpty(pos) :
				LowDetailIncompleteFullDataSource.createEmpty(pos);
	}
	
	/** 
	 * Populates the given data source using the given array of files
	 * @param usePooledDataSources if enabled the data sources necessary for this sampling will not be stored beyond what is necessary for the sampling.
	 *                              This helps reduce garbage collector pressure if the data sources will never be used again.
	 */
	protected CompletableFuture<IIncompleteFullDataSource> sampleFromFileArray(IIncompleteFullDataSource recipientFullDataSource, ArrayList<FullDataMetaFile> existingFiles, boolean usePooledDataSources)
	{
		boolean showFullDataFileSampling = Config.Client.Advanced.Debugging.DebugWireframe.showFullDataFileSampling.get();
		if (showFullDataFileSampling)
		{
			DebugRenderer.makeParticle(new DebugRenderer.BoxParticle(
					new DebugRenderer.Box(recipientFullDataSource.getSectionPos(), 64f, 72f, 0.03f, Color.MAGENTA),
					0.2, 32f));
		}
		
		// read in the existing data
		final ArrayList<CompletableFuture<IFullDataSource>> sampleDataFutures = new ArrayList<>(existingFiles.size());
		for (int i = 0; i < existingFiles.size(); i++)
		{
			FullDataMetaFile existingFile = existingFiles.get(i);
			
			
			CompletableFuture<IFullDataSource> loadFileFuture = usePooledDataSources ? existingFile.getOrLoadCachedDataSourceAsync() : existingFile.getDataSourceWithoutCachingAsync();
			
			CompletableFuture<IFullDataSource> sampleSourceFuture = loadFileFuture.whenComplete((existingFullDataSource, ex) ->
			{
				if (existingFullDataSource == null || ex != null)
				{
					// Ignore file read errors
					//LOGGER.warn(recipientFullDataSource.getSectionPos()+" sample from, file read error for file "+existingFile.pos+": "+ex.getMessage(), ex);
					return;
				}
				
				if (showFullDataFileSampling)
				{
					DebugRenderer.makeParticle(new DebugRenderer.BoxParticle(
							new DebugRenderer.Box(recipientFullDataSource.getSectionPos(), 64f, 72f, 0.03f, Color.MAGENTA.darker()),
							0.2, 32f));
				}
				
				try
				{
					recipientFullDataSource.sampleFrom(existingFullDataSource);
				}
				catch (Exception e)
				{
					LOGGER.warn("Unable to sample "+existingFullDataSource.getSectionPos()+" into "+recipientFullDataSource.getSectionPos(), e);
					//throw e;
				}
				
				// pooling temporary data sources massively reduces garbage collector overhead when just sampling (going from ~8 GB/sec to ~90 MB/sec)
				if (!usePooledDataSources && !existingFile.cacheLoadingDataSource)
				{
					existingFile.clearCachedDataSource();
					
					// get the data loader
					AbstractFullDataSourceLoader dataSourceLoader;
					if (existingFile.fullDataSourceLoader != null)
					{
						dataSourceLoader = existingFile.fullDataSourceLoader;
					}
					else
					{
						// shouldn't normally happen, but sometimes does
						dataSourceLoader = AbstractFullDataSourceLoader.getLoader(existingFile.baseMetaData.dataTypeId, existingFile.baseMetaData.binaryDataFormatVersion);
					}
					
					dataSourceLoader.returnPooledDataSource(existingFullDataSource);
				}
			});
			
			sampleDataFutures.add(sampleSourceFuture);
		}
		return CompletableFuture.allOf(sampleDataFutures.toArray(new CompletableFuture[0]))
				.thenApply(voidObj -> recipientFullDataSource);
	}
	
	protected void makeFiles(ArrayList<DhSectionPos> posList, ArrayList<FullDataMetaFile> output)
	{
		for (DhSectionPos missingPos : posList)
		{
			FullDataMetaFile newFile = this.getLoadOrMakeFile(missingPos, true);
			if (newFile != null)
			{
				output.add(newFile);
			}
		}
	}
	
	@Override
	public CompletableFuture<IFullDataSource> onDataFileCreatedAsync(FullDataMetaFile file)
	{
		DhSectionPos pos = file.pos;
		IIncompleteFullDataSource source = this.makeEmptyDataSource(pos);
		ArrayList<FullDataMetaFile> existFiles = new ArrayList<>();
		ArrayList<DhSectionPos> missing = new ArrayList<>();
		this.getDataFilesForPosition(pos, pos, existFiles, missing);
		LodUtil.assertTrue(!missing.isEmpty() || !existFiles.isEmpty());
		if (missing.size() == 1 && existFiles.isEmpty() && missing.get(0).equals(pos))
		{
			// None exist.
			return CompletableFuture.completedFuture(source);
		}
		else
		{
			this.makeFiles(missing, existFiles);
			return this.sampleFromFileArray(source, existFiles, true).thenApply(IIncompleteFullDataSource::tryPromotingToCompleteDataSource)
					.exceptionally((e) ->
					{
						FullDataMetaFile newMetaFile = this.removeCorruptedFile(pos, file, e);
						return null;
					});
		}
	}
	protected FullDataMetaFile removeCorruptedFile(DhSectionPos pos, FullDataMetaFile metaFile, Throwable exception)
	{
		LOGGER.error("Error reading Data file [" + pos + "]", exception);
		
		FileUtil.renameCorruptedFile(metaFile.file);
		// remove the FullDataMetaFile since the old one was corrupted
		this.metaFileBySectionPos.remove(pos);
		// create a new FullDataMetaFile to write new data to
		return this.getLoadOrMakeFile(pos, true);
	}
	
	
	
	//==========================//
	// executor handler methods //
	//==========================//
	
	/**
	 * Creates a new executor. <br>
	 * Does nothing if an executor already exists.
	 */
	public static void setupExecutorService()
	{
		// static setup
		if (configListener == null)
		{
			configListener = new ConfigChangeListener<>(Config.Client.Advanced.MultiThreading.numberOfFileHandlerThreads, (threadCount) -> { setThreadPoolSize(threadCount); });
		}
		
		
		if (fileHandlerThreadPool == null || fileHandlerThreadPool.isTerminated())
		{
			LOGGER.info("Starting " + FullDataFileHandler.class.getSimpleName());
			setThreadPoolSize(Config.Client.Advanced.MultiThreading.numberOfFileHandlerThreads.get());
		}
	}
	public static void setThreadPoolSize(int threadPoolSize)
	{
		if (fileHandlerThreadPool != null)
		{
			// close the previous thread pool if one exists
			fileHandlerThreadPool.shutdown();
		}
		
		fileHandlerThreadPool = ThreadUtil.makeRateLimitedThreadPool(threadPoolSize, FullDataFileHandler.class.getSimpleName() + "Thread", Config.Client.Advanced.MultiThreading.runTimeRatioForFileHandlerThreads);
	}
	
	/**
	 * Stops any executing tasks and destroys the executor. <br>
	 * Does nothing if the executor isn't running.
	 */
	public static void shutdownExecutorService()
	{
		if (fileHandlerThreadPool != null)
		{
			LOGGER.info("Stopping " + FullDataFileHandler.class.getSimpleName());
			fileHandlerThreadPool.shutdownNow();
		}
	}
	
	@Override
	public ExecutorService getIOExecutor() { return fileHandlerThreadPool; }
	
	
	
	//=========//
	// cleanup //
	//=========//
	
	@Override
	public void close() { FullDataMetaFile.checkAndLogPhantomDataSourceLifeCycles(); }
	
	
	
	//================//
	// helper methods //
	//================//
	
	@Override
	public File computeDataFilePath(DhSectionPos pos) { return new File(this.saveDir, pos.serialize() + FullDataMetaFile.FILE_SUFFIX); }
	
}
