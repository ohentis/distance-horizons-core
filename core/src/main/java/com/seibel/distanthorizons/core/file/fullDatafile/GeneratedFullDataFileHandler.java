package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IIncompleteFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.generation.WorldGenerationQueue;
import com.seibel.distanthorizons.core.generation.tasks.IWorldGenTaskTracker;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenResult;
import com.seibel.distanthorizons.core.level.DhLevel;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

public class GeneratedFullDataFileHandler extends FullDataFileHandler
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private final AtomicReference<WorldGenerationQueue> worldGenQueueRef = new AtomicReference<>(null);
	
	private final ArrayList<IOnWorldGenCompleteListener> onWorldGenTaskCompleteListeners = new ArrayList<>();

	// Use to hold onto incomplete data sources that are waiting for generation, so that they don't get GC'd before they are generated
	private final ConcurrentHashMap<DhSectionPos, IIncompleteFullDataSource> incompleteDataSources = new ConcurrentHashMap<>();
	
	public GeneratedFullDataFileHandler(IDhServerLevel level, AbstractSaveStructure saveStructure) { super(level, saveStructure); }
	
	
	
	//======//
	// data //
	//======//
	
	@Override
	public CompletableFuture<IFullDataSource> read(DhSectionPos pos)
	{
		return super.read(pos);
	}
	
	
	
	//==================//
	// generation queue //
	//==================//
	
	/** Assumes there isn't a pre-existing queue. */
	public void setGenerationQueue(WorldGenerationQueue newWorldGenQueue)
	{
		boolean oldQueueExists = this.worldGenQueueRef.compareAndSet(null, newWorldGenQueue);
		LodUtil.assertTrue(oldQueueExists, "previous world gen queue is still here!");
		LOGGER.info("Set world gen queue for level {} to start.", this.level);
		this.ForEachFile(metaFile -> {
			IFullDataSource data = metaFile.getCachedDataSourceNowOrNull();
			if (data instanceof CompleteFullDataSource) return;
			metaFile.genQueueChecked = false; // unset it so it can be checked again
			if (data != null) {
				metaFile.markNeedUpdate();
			}
		});
		flushAndSave(); // Trigger an update to the meta files
	}
	
	public void clearGenerationQueue() {
		this.worldGenQueueRef.set(null);
		incompleteDataSources.clear(); // clear the incomplete data sources
	}

	public void removeGenRequestIf(Function<DhSectionPos, Boolean> removeIf) {
		this.incompleteDataSources.forEach((pos, dataSource) ->
		{
			if (removeIf.apply(pos)) {
				this.incompleteDataSources.remove(pos);
			}
		});
	}
	
	//=================//
	// event listeners //
	//=================//
	
	public void addWorldGenCompleteListener(IOnWorldGenCompleteListener listener) { this.onWorldGenTaskCompleteListeners.add(listener); }
	
	public void removeWorldGenCompleteListener(IOnWorldGenCompleteListener listener) { this.onWorldGenTaskCompleteListeners.remove(listener); }
	
	private IFullDataSource tryPromoteDataSource(IIncompleteFullDataSource source) {
		IFullDataSource newSource = source.tryPromotingToCompleteDataSource();
		if (newSource instanceof CompleteFullDataSource) {
			incompleteDataSources.remove(source.getSectionPos());
		}
		return newSource;
	}
	
	//========//
	// events //
	//========//

	@Nullable
	private CompletableFuture<IFullDataSource> tryStartGenTask(FullDataMetaFile file, IIncompleteFullDataSource dataSource) {
		WorldGenerationQueue worldGenQueue = this.worldGenQueueRef.get();
		// breaks down the missing positions into the desired detail level that the gen queue could accept
		if (worldGenQueue != null && !file.genQueueChecked) {
			DhSectionPos pos = file.pos;
			file.genQueueChecked = true;
			byte maxSectDataDetailLevel = worldGenQueue.largestDataDetail;
			byte targetDataDetailLevel = dataSource.getDataDetailLevel();

			if (targetDataDetailLevel > maxSectDataDetailLevel) {
				ArrayList<FullDataMetaFile> existingFiles = new ArrayList<>();
				byte sectDetailLevel = (byte) (DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL + maxSectDataDetailLevel);
				pos.forEachChildAtLevel(sectDetailLevel, p -> existingFiles.add(getLoadOrMakeFile(p, true)));
				return sampleFromFileArray(dataSource, existingFiles).thenApply(this::tryPromoteDataSource)
						.exceptionally((e) ->
						{
							FullDataMetaFile newMetaFile = removeCorruptedFile(pos, file, e);
							return null;
						});
			}
			else {
				this.incompleteDataSources.put(pos, dataSource);
				// queue this section to be generated
				GenTask genTask = new GenTask(pos, new WeakReference<>(dataSource));
				worldGenQueue.submitGenTask(new DhLodPos(pos), dataSource.getDataDetailLevel(), genTask)
						.whenComplete((genTaskResult, ex) ->
						{
							if (genTaskResult.success) {
								this.onWorldGenTaskComplete(genTaskResult, ex, genTask, pos);
								this.fireOnGenPosSuccessListeners(pos);
							}
							else {
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
	private CompletableFuture<IFullDataSource> updateFromExistingDataSources(FullDataMetaFile file, IIncompleteFullDataSource data)
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
			return this.sampleFromFileArray(data, existingFiles).thenApply(this::tryPromoteDataSource)
					.exceptionally((e) ->
					{
						this.removeCorruptedFile(pos, file, e);
						return null;
					});
		}
	}

	@Override
	public CompletableFuture<IFullDataSource> onCreateDataFile(FullDataMetaFile file)
	{
		DhSectionPos pos = file.pos;
		IIncompleteFullDataSource data = makeEmptyDataSource(pos);
		CompletableFuture<IFullDataSource> future = updateFromExistingDataSources(file, data);
		// Cant start gen task, so return the data
		return future == null ? CompletableFuture.completedFuture(data) : future;
	}

	@Override
	public CompletableFuture<IFullDataSource> onDataFileUpdate(IFullDataSource source, FullDataMetaFile file,
															   Consumer<IFullDataSource> onUpdated, Function<IFullDataSource, Boolean> updater)
	{
		boolean changed = updater.apply(source);
		LodUtil.assertTrue(file.doesFileExist || changed);

		if (source instanceof IIncompleteFullDataSource)
		{
			IFullDataSource newSource = tryPromoteDataSource((IIncompleteFullDataSource) source);
			changed |= newSource != source;
			source = newSource;
		}

		if (source instanceof CompleteFullDataSource)
		{
			this.fireOnGenPosSuccessListeners(source.getSectionPos());
		}
		this.fireOnGenPosSuccessListeners(source.getSectionPos());

		if (source instanceof IIncompleteFullDataSource && !file.genQueueChecked)
		{
			WorldGenerationQueue worldGenQueue = this.worldGenQueueRef.get();
			if (worldGenQueue != null)
			{
				CompletableFuture<IFullDataSource> future = this.updateFromExistingDataSources(file, (IIncompleteFullDataSource) source);
				if (future != null)
				{
					return future.thenApply((newSource) ->
					{
						onUpdated.accept(newSource);
						return newSource;
					});
				}
			}
		}

		if (changed)
		{
			onUpdated.accept(source);
		}
		return CompletableFuture.completedFuture(source);
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
		public boolean isMemoryAddressValid() {
			IFullDataSource ref = this.targetFullDataSourceRef.get();
			return ref != null && !((IIncompleteFullDataSource)ref).hasBeenPromoted();
		}
		
		@Override
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
				if (chunkSizedFullDataSource.getLodPos().overlapsExactly(this.loadedTargetFullDataSource.getSectionPos().getSectionBBoxPos()))
				{
					((DhLevel)level).saveWrites(chunkSizedFullDataSource);
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
