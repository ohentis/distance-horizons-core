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
import com.seibel.distanthorizons.core.generation.IWorldGenerationQueue;
import com.seibel.distanthorizons.core.generation.MissingWorldGenPositionFinder;
import com.seibel.distanthorizons.core.generation.tasks.IWorldGenTaskTracker;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenResult;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.File;
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
	
	/** Used to prevent world gen tasks from being queued multiple times. */
	private final Set<DhSectionPos> generatingDataPos = Collections.newSetFromMap(new ConcurrentHashMap<>());
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public GeneratedFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure) { super(level, saveStructure); }
	public GeneratedFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure, @Nullable File saveDirOverride) { super(level, saveStructure, saveDirOverride); }
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override
	public IFullDataSource get(DhSectionPos pos) { return this.get(pos, true); }
	public IFullDataSource get(DhSectionPos pos, boolean runWorldGenCheck)
	{
		IFullDataSource dataSource = super.get(pos);
		
		if (runWorldGenCheck)
		{
			// add world gen tasks for missing columns in the data source
			// if this position hasn't already been queued for generation
			IWorldGenerationQueue worldGenQueue = this.worldGenQueueRef.get();
			if (worldGenQueue != null && !this.generatingDataPos.contains(pos))
			{
				this.queueWorldGenForMissingColumnsInDataSource(worldGenQueue, pos, dataSource);
			}
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
		this.generatingDataPos.clear(); // clear the incomplete data sources
	}
	
	/** Can be used to remove positions that are outside the player's render distance. */
	public void removeGenRequestIf(Function<DhSectionPos, Boolean> removeIf)
	{
		HashSet<DhSectionPos> removedRequests = new HashSet<>();
		
		this.generatingDataPos.forEach((pos) ->
		{
			if (removeIf.apply(pos))
			{
				this.generatingDataPos.remove(pos);
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
	
	
	
	//========//
	// events //
	//========//
	
	private void onWorldGenTaskComplete(WorldGenResult genTaskResult, Throwable exception)
	{
		if (exception != null)
		{
			// don't log shutdown exceptions
			if (!(exception instanceof CancellationException || exception.getCause() instanceof CancellationException))
			{
				LOGGER.error("Uncaught Gen Task Exception at [" + genTaskResult.pos + "], error: ["+ exception.getMessage() + "].", exception);
			}
		}
		else if (genTaskResult.success)
		{
			this.fireOnGenPosSuccessListeners(genTaskResult.pos);
			return;
		}
		else
		{
			// generation didn't complete
			LOGGER.debug("Gen Task Failed at " + genTaskResult.pos);
		}
		
		
		// if the generation task was split up into smaller positions, add the on-complete event to them
		for (CompletableFuture<WorldGenResult> siblingFuture : genTaskResult.childFutures)
		{
			siblingFuture.whenComplete((siblingGenTaskResult, siblingEx) -> this.onWorldGenTaskComplete(siblingGenTaskResult, siblingEx));
		}
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
			// try not to re-queue already generating tasks
			if (this.generatingDataPos.contains(genPos))
			{
				continue;
			}
			
			if (this.repo.existsWithPrimaryKey(genPos.serialize()))
			{
				continue;
			}
			
			
			// queue each new gen task
			GenTask genTask = new GenTask(dataSource.getSectionPos());
			CompletableFuture<WorldGenResult> worldGenFuture = worldGenQueue.submitGenTask(genPos, dataSource.getDataDetailLevel(), genTask);
			worldGenFuture.whenComplete((genTaskResult, ex) -> this.onWorldGenTaskComplete(genTaskResult, ex));
			
			taskFutureList.add(worldGenFuture);
		}
		
		
		// mark the data source as generating if necessary
		if (taskFutureList.size() != 0)
		{
			this.generatingDataPos.add(pos);
			CompletableFuture.allOf(taskFutureList.toArray(new CompletableFuture[0]))
				.whenComplete((voidObj, ex) ->
				{
					this.generatingDataPos.remove(pos);
				});
		}
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	// TODO may not be needed
	private class GenTask implements IWorldGenTaskTracker
	{
		private final DhSectionPos pos;
		
		public GenTask(DhSectionPos pos)
		{
			this.pos = pos;
		}
		
		
		
		@Override
		public boolean isMemoryAddressValid() { return true; }
		
		@Override
		public Consumer<ChunkSizedFullDataAccessor> getChunkDataConsumer()
		{
			return (chunkSizedFullDataSource) ->
			{
				GeneratedFullDataFileHandler.this.level.updateDataSourcesWithChunkData(chunkSizedFullDataSource);
			};
		}
	}
	
	/** used by external event listeners */
	@FunctionalInterface
	public interface IOnWorldGenCompleteListener
	{
		/** Fired whenever a section has completed generating */
		void onWorldGenTaskComplete(DhSectionPos pos);
		
	}
	
}
