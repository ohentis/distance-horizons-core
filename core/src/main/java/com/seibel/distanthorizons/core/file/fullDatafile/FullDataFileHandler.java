package com.seibel.distanthorizons.core.file.fullDatafile;

import com.google.common.collect.HashMultimap;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.HighDetailIncompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.LowDetailIncompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IIncompleteFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.util.FileScanUtil;
import com.seibel.distanthorizons.core.util.FileUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.seibel.distanthorizons.core.util.FileScanUtil.LOD_FILE_POSTFIX;

public class FullDataFileHandler implements IFullDataSourceProvider
{
	public static final boolean USE_LAZY_LOADING = true;

    private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	protected static ExecutorService fileHandlerThreadPool;
	protected static ConfigChangeListener<Integer> configListener;
	
	private final ConcurrentHashMap<DhSectionPos, File> unloadedFiles = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<DhSectionPos, FullDataMetaFile> fileBySectionPos = new ConcurrentHashMap<>();
	public void ForEachFile(Consumer<FullDataMetaFile> consumer) { this.fileBySectionPos.values().forEach(consumer); }
	
	private LinkedList<Consumer<IFullDataSource>> onUpdatedListeners = new LinkedList<>();
	
	protected final IDhLevel level;
	protected final File saveDir;
	protected final AtomicInteger topDetailLevel = new AtomicInteger(0);
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
		FileScanUtil.scanFiles(saveStructure, level.getLevelWrapper(), this, null);
    }
	
	// constructor helpers //
	
	/**
	 * Caller must ensure that this method is called only once,
	 *  and that the {@link FullDataFileHandler} is not used before this method is called.
	 */
	@Override
	public void addScannedFile(Collection<File> detectedFiles)
	{
		if (USE_LAZY_LOADING) {
			lazyAddScannedFile(detectedFiles);
		}
		else {
			immediateAddScannedFile(detectedFiles);
		}
	}

	private void lazyAddScannedFile(Collection<File> detectedFiles) {
		for (File file : detectedFiles) {
			try {
				DhSectionPos pos = decodePositionByFile(file);
				if (pos != null) {
					unloadedFiles.put(pos, file);
					this.topDetailLevel.updateAndGet(oldDetailLevel -> Math.max(oldDetailLevel, pos.sectionDetailLevel));
				}
			}
			catch (Exception e) {
				LOGGER.error("Failed to read data meta file at " + file + ": ", e);
				FileUtil.renameCorruptedFile(file);
			}
		}
	}

	private void immediateAddScannedFile(Collection<File> detectedFiles)
	{
        HashMultimap<DhSectionPos, FullDataMetaFile> filesByPos = HashMultimap.create();
        { // Sort files by pos.
            for (File file : detectedFiles)
			{
                try
				{
                    FullDataMetaFile metaFile = new FullDataMetaFile(this, this.level, file);
                    filesByPos.put(metaFile.pos, metaFile);
                }
				catch (IOException e)
				{
                    LOGGER.error("Failed to read data meta file at "+file+": ", e);
                    FileUtil.renameCorruptedFile(file);
                }
            }
        }

        // Warn for multiple files with the same pos, and then select the one with the latest timestamp.
        for (DhSectionPos pos : filesByPos.keySet())
		{
            Collection<FullDataMetaFile> metaFiles = filesByPos.get(pos);
            FullDataMetaFile fileToUse;
            if (metaFiles.size() > 1)
			{
//                fileToUse = Collections.max(metaFiles, Comparator.comparingLong(a -> a.metaData.dataVersion.get()));
				
                fileToUse = Collections.max(metaFiles, Comparator.comparingLong(fullDataMetaFile -> fullDataMetaFile.file.lastModified()));
                {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Multiple files with the same pos: ");
                    sb.append(pos);
                    sb.append("\n");
                    for (FullDataMetaFile metaFile : metaFiles)
					{
                        sb.append("\t");
                        sb.append(metaFile.file);
                        sb.append("\n");
                    }
                    sb.append("\tUsing: ");
                    sb.append(fileToUse.file);
                    sb.append("\n");
                    sb.append("(Other files will be renamed by appending \".old\" to their name.)");
                    LOGGER.warn(sb.toString());

                    // Rename all other files with the same pos to .old
                    for (FullDataMetaFile metaFile : metaFiles)
					{
                        if (metaFile == fileToUse)
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
            }
			else
			{
                fileToUse = metaFiles.iterator().next();
            }
            // Add file to the list of files.
			this.topDetailLevel.updateAndGet(oldDetailLevel -> Math.max(oldDetailLevel, fileToUse.pos.sectionDetailLevel));
			this.fileBySectionPos.put(pos, fileToUse);
        }
    }
	
	
	
	
    protected FullDataMetaFile getLoadOrMakeFile(DhSectionPos pos, boolean allowCreateFile)
	{
        FullDataMetaFile metaFile = this.fileBySectionPos.get(pos);
		if (metaFile != null) return metaFile;

		File fileToLoad = this.unloadedFiles.get(pos);
		// File does exist, but not loaded yet.
		if (fileToLoad != null)
		{
			synchronized (this)
			{
				// Double check locking for loading file, as loading file means also loading the metadata, which
				// while not... Very expensive, is still better to avoid multiple threads doing it, and dumping the
				// duplicated work to the trash. Therefore, eating the overhead of 'synchronized' is worth it.
				metaFile = this.fileBySectionPos.get(pos);
				if (metaFile != null)
				{
					return metaFile; // someone else loaded it already.
				}
				
				try
				{
					metaFile = new FullDataMetaFile(this, this.level, fileToLoad);
					this.topDetailLevel.updateAndGet(oldDetailLevel -> Math.max(oldDetailLevel, pos.sectionDetailLevel));
					this.fileBySectionPos.put(pos, metaFile);
					return metaFile;
				}
				catch (IOException e)
				{
					LOGGER.error("Failed to read data meta file at " + fileToLoad + ": ", e);
					FileUtil.renameCorruptedFile(fileToLoad);
				}
				finally
				{
					this.unloadedFiles.remove(pos);
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
			metaFile = new FullDataMetaFile(this, this.level, pos);
		}
		catch (IOException e)
		{
			LOGGER.error("IOException on creating new data file at {}", pos, e);
			return null;
		}
		
		this.topDetailLevel.updateAndGet(oldDetailLevel -> Math.max(oldDetailLevel, pos.sectionDetailLevel));
		
		// This is a CAS with expected null value.
		FullDataMetaFile metaFileCas = this.fileBySectionPos.putIfAbsent(pos, metaFile);
		return metaFileCas == null ? metaFile : metaFileCas;
    }
	
	/**
	 * Populates the preexistingFiles and missingFilePositions ArrayLists.
	 * 
	 * @param preexistingFiles the list of {@link FullDataMetaFile}'s that have been created for the given position.
	 * @param missingFilePositions the list of {@link DhSectionPos}'s that don't have {@link FullDataMetaFile} created for them yet.
	 */
    protected void getDataFilesForPosition(DhSectionPos effectivePos, DhSectionPos posAreaToGet,
			ArrayList<FullDataMetaFile> preexistingFiles, ArrayList<DhSectionPos> missingFilePositions)
	{
        byte sectionDetail = posAreaToGet.sectionDetailLevel;
        boolean allEmpty = true;
		
		// get all existing files for this position
        outerLoop:
        while (--sectionDetail >= this.minDetailLevel)
		{
            DhLodPos minPos = posAreaToGet.getCorner().getCornerLodPos(sectionDetail);
            int count = posAreaToGet.getSectionBBoxPos().getWidthAtDetail(sectionDetail);
			
            for (int xOffset = 0; xOffset < count; xOffset++)
			{
                for (int zOffset = 0; zOffset < count; zOffset++)
				{
                    DhSectionPos subPos = new DhSectionPos(sectionDetail, xOffset+minPos.x, zOffset+minPos.z);
                    LodUtil.assertTrue(posAreaToGet.overlaps(effectivePos) && subPos.overlaps(posAreaToGet));

                    //TODO: The following check is temporary as we only sample corner points, which means
                    // on a very different level, we may not need the entire section at all.
                    if (!CompleteFullDataSource.firstDataPosCanAffectSecond(effectivePos, subPos))
					{
						continue;
					}
					
					// check if a file for this pos exists, either loaded and unloaded
					if (this.fileBySectionPos.containsKey(subPos) || this.unloadedFiles.containsKey(subPos))
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
			if (this.unloadedFiles.containsKey(childPos))
			{
				this.getLoadOrMakeFile(childPos, true);
			}
			
			
			FullDataMetaFile metaFile = this.fileBySectionPos.get(childPos);
			if (metaFile != null)
			{
				// we have reached a populated leaf node in the quad tree
				preexistingFiles.add(metaFile);
			}
			else if (childPos.sectionDetailLevel == this.minDetailLevel)
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
	
	
	/**
	 * Returns the {@link IFullDataSource} for the given section position. <Br>
	 * The returned data source may be null. <Br> <Br>
	 *
	 * For now, if result is null, it prob means error has occurred when loading or creating the file object. <Br> <Br>
	 *
	 * This call is concurrent. I.e. it supports being called by multiple threads at the same time.
	 */
	@Override
    public CompletableFuture<IFullDataSource> read(DhSectionPos pos)
	{
		this.topDetailLevel.updateAndGet(oldDetailLevel -> Math.max(oldDetailLevel, pos.sectionDetailLevel));
        FullDataMetaFile metaFile = this.getLoadOrMakeFile(pos, true);
        if (metaFile == null)
		{
			return CompletableFuture.completedFuture(null);
		}
		
		
		// future wrapper necessary in order to handle file read errors
		CompletableFuture<IFullDataSource> futureWrapper = new CompletableFuture<>();
		metaFile.loadOrGetCachedDataSourceAsync().exceptionally((e) ->
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
	
    /** This call is concurrent. I.e. it supports being called by multiple threads at the same time. */
    @Override
    public void write(DhSectionPos sectionPos, ChunkSizedFullDataAccessor chunkDataView)
	{
        DhLodPos chunkPos = chunkDataView.getLodPos();
        LodUtil.assertTrue(chunkPos.overlapsExactly(sectionPos.getSectionBBoxPos()), "Chunk "+chunkPos+" does not overlap section "+sectionPos);
		
        chunkPos = chunkPos.convertToDetailLevel((byte) this.minDetailLevel);
		this.writeChunkDataToMetaFile(new DhSectionPos(chunkPos.detailLevel, chunkPos.x, chunkPos.z), chunkDataView);
    }
    private void writeChunkDataToMetaFile(DhSectionPos sectionPos, ChunkSizedFullDataAccessor chunkData)
	{
        FullDataMetaFile metaFile = this.fileBySectionPos.get(sectionPos);
        if (metaFile != null)
		{ 
			// there is a file for this position
            metaFile.addToWriteQueue(chunkData);
        }
		
        if (sectionPos.sectionDetailLevel <= this.topDetailLevel.get())
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
        for (FullDataMetaFile metaFile : this.fileBySectionPos.values())
		{
            futures.add(metaFile.flushAndSaveAsync());
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

	@Override
	public CompletableFuture<Void> flushAndSave(DhSectionPos sectionPos)
	{
		FullDataMetaFile metaFile = this.fileBySectionPos.get(sectionPos);
		if (metaFile == null)
		{
			return CompletableFuture.completedFuture(null);
		}
		return metaFile.flushAndSaveAsync();
	}
	
	
	@Override
	public synchronized void addOnUpdatedListener(Consumer<IFullDataSource> listener)
	{
		this.onUpdatedListeners.add(listener);
	}
	
	protected IIncompleteFullDataSource makeEmptyDataSource(DhSectionPos pos)
	{
		return pos.sectionDetailLevel <= HighDetailIncompleteFullDataSource.MAX_SECTION_DETAIL ?
				HighDetailIncompleteFullDataSource.createEmpty(pos) : 
				LowDetailIncompleteFullDataSource.createEmpty(pos);
	}
	
	/** populates the given data source using the given array of files */
	protected CompletableFuture<IIncompleteFullDataSource> sampleFromFileArray(IIncompleteFullDataSource recipientFullDataSource, ArrayList<FullDataMetaFile> existingFiles)
	{
		// read in the existing data
		final ArrayList<CompletableFuture<Void>> loadDataFutures = new ArrayList<>(existingFiles.size());
		for (FullDataMetaFile existingFile : existingFiles)
		{
			loadDataFutures.add(existingFile.loadOrGetCachedDataSourceAsync()
				.exceptionally((ex) -> /*Ignore file read errors*/null)
				.thenAccept((existingFullDataSource) ->
				{
					if (existingFullDataSource == null)
					{
						return;
					}
					
					//LOGGER.info("Merging data from {} into {}", data.getSectionPos(), pos);
					recipientFullDataSource.sampleFrom(existingFullDataSource);
				})
			);
		}
		return CompletableFuture.allOf(loadDataFutures.toArray(new CompletableFuture[0])).thenApply(voidObj -> recipientFullDataSource);
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
    public CompletableFuture<IFullDataSource> onCreateDataFile(FullDataMetaFile file)
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
			return this.sampleFromFileArray(source, existFiles).thenApply(IIncompleteFullDataSource::tryPromotingToCompleteDataSource)
				.exceptionally((e) ->
				{
					FullDataMetaFile newMetaFile = this.removeCorruptedFile(pos, file, e);
					return null;
				});
        }
    }
	protected FullDataMetaFile removeCorruptedFile(DhSectionPos pos, FullDataMetaFile metaFile, Throwable exception)
	{
		LOGGER.error("Error reading Data file ["+pos+"]", exception);
		
		FileUtil.renameCorruptedFile(metaFile.file);
		// remove the FullDataMetaFile since the old one was corrupted
		this.fileBySectionPos.remove(pos);
		// create a new FullDataMetaFile to write new data to
		return this.getLoadOrMakeFile(pos, true);
	}
	
	@Override
    public CompletableFuture<IFullDataSource> onDataFileUpdate(IFullDataSource source, FullDataMetaFile file,
															   Consumer<IFullDataSource> onUpdated, Function<IFullDataSource, Boolean> updater)
	{
        boolean changed = updater.apply(source);
//		if (changed)
//		{
//		  metaData.dataVersion.incrementAndGet();
//		}
		
        if (source instanceof IIncompleteFullDataSource)
		{
            IFullDataSource newSource = ((IIncompleteFullDataSource) source).tryPromotingToCompleteDataSource();
            changed |= newSource != source;
            source = newSource;
        }
		
        if (changed) 
		{
			onUpdated.accept(source);
		}
        return CompletableFuture.completedFuture(source);
    }
	
    @Override
    public File computeDataFilePath(DhSectionPos pos) { return new File(this.saveDir, pos.serialize() + LOD_FILE_POSTFIX); }

	@Nullable
	public DhSectionPos decodePositionByFile(File file)
	{
		String fileName = file.getName();
		if (!fileName.endsWith(LOD_FILE_POSTFIX)) return null;
		fileName = fileName.substring(0, fileName.length() - 4);
		return DhSectionPos.deserialize(fileName);
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
			LOGGER.info("Starting "+FullDataFileHandler.class.getSimpleName());
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
		
		fileHandlerThreadPool = ThreadUtil.makeRateLimitedThreadPool(threadPoolSize, FullDataFileHandler.class.getSimpleName()+"Thread", Config.Client.Advanced.MultiThreading.runTimeRatioForFileHandlerThreads); 
	}
	
	/**
	 * Stops any executing tasks and destroys the executor. <br>
	 * Does nothing if the executor isn't running.
	 */
	public static void shutdownExecutorService()
	{
		if (fileHandlerThreadPool != null)
		{
			LOGGER.info("Stopping "+ FullDataFileHandler.class.getSimpleName());
			fileHandlerThreadPool.shutdownNow();
		}
	}
	
	@Override
	public ExecutorService getIOExecutor() { return fileHandlerThreadPool; }
	
	
	
	//=========//
	// cleanup //
	//=========//
	
    @Override
    public void close()
	{
        FullDataMetaFile.debugPhantomLifeCycleCheck();
    }
}
