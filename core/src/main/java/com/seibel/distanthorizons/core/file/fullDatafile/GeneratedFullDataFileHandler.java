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

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IIncompleteFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.generation.IWorldGenerationQueue;
import com.seibel.distanthorizons.core.generation.tasks.IWorldGenTaskTracker;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenResult;
import com.seibel.distanthorizons.core.level.DhLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

public class GeneratedFullDataFileHandler extends FullDataFileHandler
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	protected final AtomicReference<IWorldGenerationQueue> worldGenQueueRef = new AtomicReference<>(null);
	
	private final ArrayList<IOnWorldGenCompleteListener> onWorldGenTaskCompleteListeners = new ArrayList<>();
	
	private final ConcurrentSkipListSet<Long> taskCompletionTimes = new ConcurrentSkipListSet<>();
	private final F3Screen.DynamicMessage handlerF3Message;
	
	// Use to hold onto incomplete data sources that are waiting for generation, so that they don't get GC'd before they are generated
	private final ConcurrentHashMap<DhSectionPos, IIncompleteFullDataSource> incompleteDataSources = new ConcurrentHashMap<>();
	
	public GeneratedFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure) {
		super(level, saveStructure);
		this.handlerF3Message = new F3Screen.DynamicMessage(() ->
		{
			// Keep only for last 30 seconds
			taskCompletionTimes.removeIf(time -> time < System.currentTimeMillis() - 30000);
			if (taskCompletionTimes.size() < 2)
				return "Gen task completion time: No information yet";
			
			double timePerCompletion = (double) (taskCompletionTimes.last() - taskCompletionTimes.first()) / taskCompletionTimes.size() / 1000;
			if (timePerCompletion < 1) {
				double completionRate = 1 / timePerCompletion;
				return "Gen task completion rate: " + new DecimalFormat("#.00").format(completionRate) + " completions/sec";
			} else {
				return "Gen task completion time: " + new DecimalFormat("#.00").format(timePerCompletion) + " seconds/completion";
			}
		});
	}
	
	@Override
	public void close()
	{
		super.close();
		this.handlerF3Message.close();
	}
	
	//==================//
	// generation queue //
	//==================//
	
	/** Assumes there isn't a pre-existing queue. */
	public void setGenerationQueue(IWorldGenerationQueue newWorldGenQueue)
	{
		boolean oldQueueExists = this.worldGenQueueRef.compareAndSet(null, newWorldGenQueue);
		LodUtil.assertTrue(oldQueueExists, "previous world gen queue is still here!");
		LOGGER.info("Set world gen queue for level {} to start.", this.level);
		this.ForEachFile(metaFile -> {
			IFullDataSource data = metaFile.getCachedDataSourceNowOrNull();
			if (data instanceof CompleteFullDataSource) return;
			metaFile.genQueueChecked = false; // unset it so it can be checked again
			if (data != null)
			{
				metaFile.markNeedsUpdate();
			}
		});
		flushAndSave(); // Trigger an update to the meta files
	}
	
	public void clearGenerationQueue()
	{
		this.worldGenQueueRef.set(null);
		incompleteDataSources.clear(); // clear the incomplete data sources
	}
	
	public void removeGenRequestIf(Function<DhSectionPos, Boolean> removeIf)
	{
		HashSet<DhSectionPos> removedRequests = new HashSet<>();
		
		this.incompleteDataSources.forEach((pos, dataSource) ->
		{
			if (removeIf.apply(pos))
			{
				this.incompleteDataSources.remove(pos);
				removedRequests.add(pos);
			}
		});
		
		this.worldGenQueueRef.get().cancelGenTasks(removedRequests);
	}
	
	//=================//
	// event listeners //
	//=================//
	
	public void addWorldGenCompleteListener(IOnWorldGenCompleteListener listener) { this.onWorldGenTaskCompleteListeners.add(listener); }
	
	public void removeWorldGenCompleteListener(IOnWorldGenCompleteListener listener) { this.onWorldGenTaskCompleteListeners.remove(listener); }
	
	private IFullDataSource tryPromoteDataSource(IIncompleteFullDataSource source)
	{
		IFullDataSource newSource = source.tryPromotingToCompleteDataSource();
		if (newSource instanceof CompleteFullDataSource)
		{
			incompleteDataSources.remove(source.getSectionPos());
		}
		return newSource;
	}
	
	//========//
	// events //
	//========//
	
	@Nullable
	private CompletableFuture<IFullDataSource> tryStartGenTask(FullDataMetaFile file, IIncompleteFullDataSource dataSource) // TODO after generation is finished, save and free any full datasources that aren't in use (IE high detail ones below the top)
	{
		IWorldGenerationQueue worldGenQueue = this.worldGenQueueRef.get();
		// breaks down the missing positions into the desired detail level that the gen queue could accept
		if (worldGenQueue != null && !file.genQueueChecked)
		{
			DhSectionPos pos = file.pos;
			file.genQueueChecked = true;
			byte maxSectDataDetailLevel = worldGenQueue.largestDataDetail();
			byte targetDataDetailLevel = dataSource.getDataDetailLevel();
			
			if (targetDataDetailLevel > maxSectDataDetailLevel)
			{
				ArrayList<FullDataMetaFile> existingFiles = new ArrayList<>();
				byte sectDetailLevel = (byte) (DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL + maxSectDataDetailLevel);
				pos.forEachChildAtLevel(sectDetailLevel, childPos -> existingFiles.add(this.getLoadOrMakeFile(childPos, true)));
				return this.sampleFromFileArray(dataSource, existingFiles, true).thenApply(this::tryPromoteDataSource)
						.exceptionally((ex) ->
						{
							FullDataMetaFile newMetaFile = this.removeCorruptedFile(pos, file, ex);
							return null;
						});
			}
			else
			{
				this.incompleteDataSources.put(pos, dataSource);
				// queue this section to be generated
				GenTask genTask = new GenTask(pos, new WeakReference<>(dataSource));
				worldGenQueue.submitGenTask(pos, dataSource.getDataDetailLevel(), genTask)
						.whenComplete((genTaskResult, ex) ->
						{
							if (genTaskResult.success)
							{
								this.onWorldGenTaskComplete(genTaskResult, ex, genTask, pos);
								this.fireOnGenPosSuccessListeners(pos);
							}
							else
							{
								file.genQueueChecked = false;
							}
							this.incompleteDataSources.remove(pos);
						});
			}
			// return the empty dataSource (it will be populated later)
			return CompletableFuture.completedFuture(dataSource);
		}
		return null;
	}
	
	// Try update the gen queue on this data source. If null, then nothing was done.
	@Nullable
	private CompletableFuture<IFullDataSource> updateFromExistingDataSourcesAsync(FullDataMetaFile file, IIncompleteFullDataSource data, boolean usePooledDataSources)
	{
		DhSectionPos pos = file.pos;
		ArrayList<FullDataMetaFile> existingFiles = new ArrayList<>();
		ArrayList<DhSectionPos> missingPositions = new ArrayList<>();
		this.getDataFilesForPosition(pos, pos, existingFiles, missingPositions);
		
		if (missingPositions.size() == 1)
		{
			// Only missing myself. I.e. no child file data exists yet.
			return this.tryStartGenTask(file, data);
		}
		else
		{
			// There are other data source files to sample from.
			this.makeFiles(missingPositions, existingFiles);
			return this.sampleFromFileArray(data, existingFiles, usePooledDataSources)
					.thenApply(this::tryPromoteDataSource)
					.exceptionally((e) ->
					{
						this.removeCorruptedFile(pos, file, e);
						return null;
					});
		}
	}
	
	@Override
	public CompletableFuture<IFullDataSource> onDataFileCreatedAsync(FullDataMetaFile file)
	{
		DhSectionPos pos = file.pos;
		IIncompleteFullDataSource data = this.makeEmptyDataSource(pos);
		CompletableFuture<IFullDataSource> future = this.updateFromExistingDataSourcesAsync(file, data, true);
		// Cant start gen task, so return the data
		return future == null ? CompletableFuture.completedFuture(data) : future;
	}
	
	@Override
	public CompletableFuture<DataFileUpdateResult> onDataFileUpdateAsync(IFullDataSource fullDataSource, FullDataMetaFile file, boolean dataChanged)
	{
		LodUtil.assertTrue(file.doesFileExist || dataChanged);
		
		
		if (fullDataSource instanceof CompleteFullDataSource)
		{
			this.incompleteDataSources.remove(fullDataSource.getSectionPos());
		}
		this.fireOnGenPosSuccessListeners(fullDataSource.getSectionPos());
		
		
		if (fullDataSource instanceof IIncompleteFullDataSource && !file.genQueueChecked)
		{
			IWorldGenerationQueue worldGenQueue = this.worldGenQueueRef.get();
			if (worldGenQueue != null)
			{
				CompletableFuture<IFullDataSource> future = this.updateFromExistingDataSourcesAsync(file, (IIncompleteFullDataSource) fullDataSource, false);
				if (future != null)
				{
					final boolean finalDataChanged = dataChanged;
					return future.thenApply((newSource) -> new DataFileUpdateResult(newSource, finalDataChanged));
				}
			}
		}
		
		return CompletableFuture.completedFuture(new DataFileUpdateResult(fullDataSource, dataChanged));
	}
	
	private void onWorldGenTaskComplete(WorldGenResult genTaskResult, Throwable exception, GenTask genTask, DhSectionPos pos)
	{
		if (exception != null)
		{
			// don't log shutdown exceptions
			if (!(exception instanceof CancellationException || exception.getCause() instanceof CancellationException))
			{
				LOGGER.error("Uncaught Gen Task Exception at " + pos + ":", exception);
			}
		}
		else if (genTaskResult.success)
		{
			// generation completed, update the files and listener(s)
			this.flushAndSave(pos);
			//this.fireOnGenPosSuccessListeners(pos);
			this.addTimestampToStatistics();
			return;
		}
		else
		{
			// generation didn't complete
			LOGGER.error("Gen Task Failed at " + pos + ": " + genTaskResult);
		}
		
		
		// if the generation task was split up into smaller positions, add the on-complete event to them
		for (CompletableFuture<WorldGenResult> siblingFuture : genTaskResult.childFutures)
		{
			siblingFuture.whenComplete((siblingGenTaskResult, siblingEx) -> this.onWorldGenTaskComplete(siblingGenTaskResult, siblingEx, genTask, pos));
		}
		
		genTask.releaseStrongReference();
	}
	
	private void addTimestampToStatistics()
	{
		taskCompletionTimes.add(System.currentTimeMillis());
	}
	
	private void fireOnGenPosSuccessListeners(DhSectionPos pos)
	{
		if (true) return;
		//LOGGER.info("gen task completed for pos: ["+pos+"].");
		
/*		// save the full data source
		FullDataMetaFile file = this.fileBySectionPos.get(pos);
		if (file != null)
		{
			// timeout is for the rare case where saving gets stuck
			int timeoutInSeconds = 10;
			try
			{
				file.flushAndSaveAsync().get(timeoutInSeconds, TimeUnit.SECONDS);
			}
			catch (TimeoutException | InterruptedException | ExecutionException e)
			{
				LOGGER.warn("fireOnGenPosSuccessListeners timed out after waiting ["+timeoutInSeconds+"] seconds for pos: ["+pos+"].");
			}
		}
		else
		{
			// doesn't appear to cause issues, but probably isn't desired either
			//LOGGER.warn("fireOnGenPosSuccessListeners file null for pos: ["+pos+"].");
		}
		*/
		// fire the event listeners 
		for (IOnWorldGenCompleteListener listener : this.onWorldGenTaskCompleteListeners)
		{
			listener.onWorldGenTaskComplete(pos);
		}
		
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	private class GenTask implements IWorldGenTaskTracker
	{
		private final DhSectionPos pos;
		
		// weak reference (probably) used to prevent overloading the GC when lots of gen tasks are created? // TODO do we still need a weak reference here?
		private final WeakReference<IFullDataSource> targetFullDataSourceRef;
		// the target data source is where the generated chunk data will be put when completed
		private IFullDataSource loadedTargetFullDataSource = null;
		
		
		
		public GenTask(DhSectionPos pos, WeakReference<IFullDataSource> targetFullDataSourceRef)
		{
			this.pos = pos;
			this.targetFullDataSourceRef = targetFullDataSourceRef;
		}
		
		
		
		@Override
		public boolean isMemoryAddressValid()
		{
			IFullDataSource ref = this.targetFullDataSourceRef.get();
			return ref != null && !((IIncompleteFullDataSource) ref).hasBeenPromoted();
		}
		
		@Override
		@Nullable
		public Consumer<ChunkSizedFullDataAccessor> getChunkDataConsumer()
		{
			if (this.loadedTargetFullDataSource == null)
			{
				this.loadedTargetFullDataSource = this.targetFullDataSourceRef.get();
			}
			if (this.loadedTargetFullDataSource == null)
			{
				return null;
			}
			
			
			return (chunkSizedFullDataSource) ->
			{
				if (chunkSizedFullDataSource.getSectionPos().overlapsExactly(this.loadedTargetFullDataSource.getSectionPos()))
				{
					((DhLevel) level).saveWrites(chunkSizedFullDataSource);
					//GeneratedFullDataFileHandler.this.write(this.loadedTargetFullDataSource.getSectionPos(), chunkSizedFullDataSource);
				}
			};
		}
		
		public void releaseStrongReference() { this.loadedTargetFullDataSource = null; }
		
	}
	
	/**
	 * used by external event listeners <br>
	 * TODO may or may not be best to have this in a separate file
	 */
	@FunctionalInterface
	public interface IOnWorldGenCompleteListener
	{
		/** Fired whenever a section has completed generating */
		void onWorldGenTaskComplete(DhSectionPos pos);
		
	}
	
}
