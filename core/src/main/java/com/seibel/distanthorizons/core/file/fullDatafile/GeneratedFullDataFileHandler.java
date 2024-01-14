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
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.generation.MissingWorldGenPositionFinder;
import com.seibel.distanthorizons.core.generation.IWorldGenerationQueue;
import com.seibel.distanthorizons.core.generation.tasks.IWorldGenTaskTracker;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenResult;
import com.seibel.distanthorizons.core.level.DhLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import org.apache.logging.log4j.Logger;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

public class GeneratedFullDataFileHandler extends FullDataFileHandler
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private final AtomicReference<IWorldGenerationQueue> worldGenQueueRef = new AtomicReference<>(null);
	
	private final ArrayList<IOnWorldGenCompleteListener> onWorldGenTaskCompleteListeners = new ArrayList<>();
	
	/** Used to prevent data sources from being garbage collected before their world gen finishes. */
	private final ConcurrentHashMap<DhSectionPos, IFullDataSource> generatingDataSourceByPos = new ConcurrentHashMap<>();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public GeneratedFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure) { super(level, saveStructure); }
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override
	public IFullDataSource get(DhSectionPos pos)
	{
		IFullDataSource dataSource = super.get(pos);
		
		// add world gen tasks for missing columns in the data source
		// if this position hasn't already been queued for generation
		IWorldGenerationQueue worldGenQueue = this.worldGenQueueRef.get();
		if (worldGenQueue != null && !this.generatingDataSourceByPos.containsKey(pos))
		{
			this.queueWorldGenForMissingColumnsInDataSource(worldGenQueue, pos, dataSource);
		}
		
		return dataSource;
	}
	
	
	
	//==================//
	// generation queue //
	//==================//
	
	/**
	 * Assigns the queue for handling world gen and does first time setup as well. <br> 
	 * Assumes there isn't a pre-existing queue. 
	 */ 
	public void setWorldGenerationQueue(IWorldGenerationQueue newWorldGenQueue)
	{
		boolean oldQueueExists = this.worldGenQueueRef.compareAndSet(null, newWorldGenQueue);
		LodUtil.assertTrue(oldQueueExists, "previous world gen queue is still here!");
		LOGGER.info("Set world gen queue for level ["+this.level+"].");
	}
	
	public void clearGenerationQueue()
	{
		this.worldGenQueueRef.set(null);
		this.generatingDataSourceByPos.clear(); // clear the incomplete data sources
	}
	
	/** Can be used to remove positions that are outside the player's render distance. */
	public void removeGenRequestIf(Function<DhSectionPos, Boolean> removeIf)
	{
		this.generatingDataSourceByPos.forEach((pos, dataSource) ->
		{
			if (removeIf.apply(pos))
			{
				this.generatingDataSourceByPos.remove(pos);
			}
		});
	}
	
	
	
	//=================//
	// event listeners //
	//=================//
	
	public void addWorldGenCompleteListener(IOnWorldGenCompleteListener listener) { this.onWorldGenTaskCompleteListeners.add(listener); }
	public void removeWorldGenCompleteListener(IOnWorldGenCompleteListener listener) { this.onWorldGenTaskCompleteListeners.remove(listener); }
	
	
	
	//========//
	// events //
	//========//
	
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
			this.fireOnGenPosSuccessListeners(pos);
			return;
		}
		else
		{
			// generation didn't complete
			LOGGER.debug("Gen Task Failed at " + pos);
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
		// fire the event listeners 
		for (IOnWorldGenCompleteListener listener : this.onWorldGenTaskCompleteListeners)
		{
			listener.onWorldGenTaskComplete(pos);
		}
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	private void queueWorldGenForMissingColumnsInDataSource(IWorldGenerationQueue worldGenQueue, DhSectionPos pos, IFullDataSource dataSource)
	{
		// get the un-generated pos list
		byte minGeneratorSectionDetailLevel = (byte) (worldGenQueue.highestDataDetail() + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		ArrayList<DhSectionPos> genPosList = MissingWorldGenPositionFinder.getUngeneratedPosList(dataSource, minGeneratorSectionDetailLevel, true);
		
		// start each pos generating
		ArrayList<CompletableFuture<WorldGenResult>>  taskFutureList = new ArrayList<>();
		for (DhSectionPos genPos : genPosList)
		{
			// queue each gen task
			GenTask genTask = new GenTask(dataSource.getSectionPos(), new WeakReference<>(dataSource));
			CompletableFuture<WorldGenResult> worldGenFuture = worldGenQueue.submitGenTask(genPos, dataSource.getDataDetailLevel(), genTask);
			worldGenFuture.whenComplete((genTaskResult, ex) ->
			{
				this.onWorldGenTaskComplete(genTaskResult, ex, genTask, genPos);
				this.onWorldGenTaskComplete(genTaskResult, ex, genTask, pos);
			});
			
			taskFutureList.add(worldGenFuture);
		}
		
		
		// mark the data source as generating if necessary
		if (taskFutureList.size() != 0)
		{
			this.generatingDataSourceByPos.put(pos, dataSource);
			CompletableFuture.allOf(taskFutureList.toArray(new CompletableFuture[0]))
				.whenComplete((voidObj, ex) ->
				{
					this.generatingDataSourceByPos.remove(pos);
				});
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
		public boolean isMemoryAddressValid() { return this.targetFullDataSourceRef.get() != null; }
		
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
				if (chunkSizedFullDataSource.getSectionPos().overlapsExactly(this.loadedTargetFullDataSource.getSectionPos()))
				{
					((DhLevel) level).updateDataSourcesWithChunkData(chunkSizedFullDataSource);
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
