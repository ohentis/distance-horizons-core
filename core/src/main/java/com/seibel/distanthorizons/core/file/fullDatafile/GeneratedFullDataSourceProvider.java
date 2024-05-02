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

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.generation.IFullDataSourceRetrievalQueue;
import com.seibel.distanthorizons.core.generation.tasks.IWorldGenTaskTracker;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenResult;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

public class GeneratedFullDataSourceProvider extends FullDataSourceProviderV2 implements IDebugRenderable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	public static final int MAX_WORLD_GEN_REQUESTS_PER_THREAD = 20;
	
	
	private final AtomicReference<IFullDataSourceRetrievalQueue> worldGenQueueRef = new AtomicReference<>(null);
	private final ArrayList<IOnWorldGenCompleteListener> onWorldGenTaskCompleteListeners = new ArrayList<>();
	
	protected final DelayedFullDataSourceSaveCache delayedFullDataSourceSaveCache = new DelayedFullDataSourceSaveCache(this::onDataSourceSave, 5_000);
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public GeneratedFullDataSourceProvider(IDhLevel level, AbstractSaveStructure saveStructure) { super(level, saveStructure); }
	public GeneratedFullDataSourceProvider(IDhLevel level, AbstractSaveStructure saveStructure, @Nullable File saveDirOverride) { super(level, saveStructure, saveDirOverride); }
	
	
	
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
				LOGGER.error("Uncaught Gen Task Exception at [" + genTaskResult.pos + "], error: [" + exception.getMessage() + "].", exception);
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
	
	// TODO only fire after the section has finished generated or once every X seconds
	private void fireOnGenPosSuccessListeners(DhSectionPos pos)
	{
		// fire the event listeners 
		for (IOnWorldGenCompleteListener listener : this.onWorldGenTaskCompleteListeners)
		{
			listener.onWorldGenTaskComplete(pos);
		}
	}
	
	
	
	//===================================//
	// world gen (data source retrieval) //
	//===================================//
	
	/**
	 * Assigns the queue for handling world gen and does first time setup as well. <br>
	 * Assumes there isn't a pre-existing queue.
	 */
	public void setWorldGenerationQueue(IFullDataSourceRetrievalQueue newWorldGenQueue)
	{
		boolean oldQueueExists = this.worldGenQueueRef.compareAndSet(null, newWorldGenQueue);
		LodUtil.assertTrue(oldQueueExists, "previous world gen queue is still here!");
		LOGGER.info("Set world gen queue for level [" + this.level + "].");
	}
	
	@Override
	public boolean canRetrieveMissingDataSources() { return true; }
	
	@Override
	public void setTotalRetrievalPositionCount(int newCount)
	{
		IFullDataSourceRetrievalQueue worldGenQueue = this.worldGenQueueRef.get();
		if (worldGenQueue != null)
		{
			worldGenQueue.setEstimatedTotalTaskCount(newCount);
		}
	}
	
	@Override
	public boolean canQueueRetrieval()
	{
		if (!super.canQueueRetrieval())
		{
			return false;
		}
		
		
		IFullDataSourceRetrievalQueue worldGenQueue = this.worldGenQueueRef.get();
		if (worldGenQueue == null)
		{
			// we can't queue anything if the world generator isn't set up yet
			return false;
		}
		
		
		ThreadPoolExecutor updateExecutor = ThreadPoolUtil.getUpdatePropagatorExecutor();
		if (updateExecutor == null || updateExecutor.getQueue().size() >= MAX_UPDATE_TASK_COUNT / 2)
		{
			// don't queue additional world gen requests if the updater is behind
			return false;
		}
		
		
		int maxQueueCount = MAX_WORLD_GEN_REQUESTS_PER_THREAD * Config.Client.Advanced.MultiThreading.numberOfWorldGenerationThreads.get();
		
		if (this.delayedFullDataSourceSaveCache.getUnsavedCount() >= maxQueueCount)
		{
			// don't queue additional world gen requests if there are
			// a lot of data sources in memory 
			// (this is done to prevent infinite memory growth)
			return false;
		}
		
		
		// don't queue additional world gen requests beyond the max allotted count
		return worldGenQueue.getWaitingTaskCount() < maxQueueCount;
	}
	
	@Override
	public boolean queuePositionForRetrieval(DhSectionPos genPos)
	{
		IFullDataSourceRetrievalQueue worldGenQueue = this.worldGenQueueRef.get();
		if (worldGenQueue == null)
		{
			return false;
		}
		
		GenTask genTask = new GenTask(genPos);
		CompletableFuture<WorldGenResult> worldGenFuture = worldGenQueue.submitGenTask(genPos, (byte) (genPos.getDetailLevel() - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL), genTask);
		worldGenFuture.whenComplete((genTaskResult, ex) -> this.onWorldGenTaskComplete(genTaskResult, ex));
		
		return true;
	}
	
	@Override
	public void removeRetrievalRequestIf(Function<DhSectionPos, Boolean> removeIf)
	{
		IFullDataSourceRetrievalQueue worldGenQueue = this.worldGenQueueRef.get();
		if (worldGenQueue != null)
		{
			worldGenQueue.removeRetrievalRequestIf(removeIf);
		}
	}
	
	@Override
	public void clearRetrievalQueue() { this.worldGenQueueRef.set(null); }
	
	@Override
	public int getUnsavedDataSourceCount() { return this.delayedFullDataSourceSaveCache.getUnsavedCount(); }
	
	
	public boolean isFullyGenerated(byte[] columnGenerationSteps)
	{
		return IntStream.range(0, columnGenerationSteps.length)
				.noneMatch(i -> columnGenerationSteps[i] == EDhApiWorldGenerationStep.EMPTY.value);
	}
	
	@Override
	public ArrayList<DhSectionPos> getPositionsToRetrieve(DhSectionPos pos)
	{
		IFullDataSourceRetrievalQueue worldGenQueue = this.worldGenQueueRef.get();
		if (worldGenQueue == null)
		{
			return null;
		}
		
		
		// don't check any child positions if this position is already fully generated 
		if (this.repo.existsWithKey(pos))
		{
			byte[] columnGenerationSteps = this.repo.getColumnGenerationStepForPos(pos);
			// shouldn't happen, but just in case
			if (columnGenerationSteps != null)
			{
				boolean positionFullyGenerated = true;
				
				// check if any positions are ungenerated
				for (int i = 0; i < columnGenerationSteps.length; i++)
				{
					if (columnGenerationSteps[i] == EDhApiWorldGenerationStep.EMPTY.value)
					{
						positionFullyGenerated = false;
						break;
					}
				}
				
				if (positionFullyGenerated)
				{
					return new ArrayList<>();
				}
			}
		}
		
		
		
		// this section is missing one or more columns, queue the missing ones for generation.
		// TODO speed up this logic by only checking ungenerated columns
		ArrayList<DhSectionPos> generationList = new ArrayList<>();
		byte minGeneratorSectionDetailLevel = (byte) (worldGenQueue.highestDataDetail() + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		pos.forEachChildAtDetailLevel(minGeneratorSectionDetailLevel, (genPos) ->
		{
			if (!this.repo.existsWithKey(genPos))
			{
				// nothing exists for this position, it needs generation
				generationList.add(genPos);
			}
			else
			{
				byte[] columnGenerationSteps = this.repo.getColumnGenerationStepForPos(genPos);
				if (columnGenerationSteps == null)
				{
					// shouldn't happen, but just in case
					return;
				}
				
				
				EDhApiWorldGenerationStep currentMinWorldGenStep = EDhApiWorldGenerationStep.LIGHT;
				checkWorldGenLoop:
				for (int x = 0; x < FullDataSourceV2.WIDTH; x++)
				{
					for (int z = 0; z < FullDataSourceV2.WIDTH; z++)
					{
						int index = FullDataSourceV2.relativePosToIndex(x, z);
						byte genStepValue = columnGenerationSteps[index];
						
						if (genStepValue < currentMinWorldGenStep.value)
						{
							EDhApiWorldGenerationStep newWorldGenStep = EDhApiWorldGenerationStep.fromValue(genStepValue);
							if (newWorldGenStep != null && newWorldGenStep.value < currentMinWorldGenStep.value)
							{
								currentMinWorldGenStep = newWorldGenStep;
							}
						}
						
						if (currentMinWorldGenStep == EDhApiWorldGenerationStep.EMPTY)
						{
							// queue the task
							break checkWorldGenLoop;
						}
					}
				}
				
				if (currentMinWorldGenStep != EDhApiWorldGenerationStep.EMPTY)
				{
					// no world gen needed for this position
					return;
				}
				
				generationList.add(genPos);
			}
		});
		
		return generationList;
	}
	
	@Override
	public int getMaxPossibleRetrievalPositionCountForPos(DhSectionPos pos)
	{
		IFullDataSourceRetrievalQueue worldGenQueue = this.worldGenQueueRef.get();
		if (worldGenQueue == null)
		{
			return -1;
		}
		
		int minGeneratorSectionDetailLevel = worldGenQueue.highestDataDetail() + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
		int detailLevelDiff = pos.getDetailLevel() - minGeneratorSectionDetailLevel;
		
		return BitShiftUtil.powerOfTwo(detailLevelDiff);
	}
	
	public Map<DhSectionPos, Integer> getLoadStates(Iterable<DhSectionPos> posList)
	{
		HashMap<DhSectionPos, Integer> map = new HashMap<>();
		for (DhSectionPos pos : posList)
		{
			map.put(pos,
					// Loaded
					this.delayedFullDataSourceSaveCache.dataSourceByPosition.containsKey(pos) ? 3
							// Unloaded, but exists
							: this.fileExists(pos) ? 2
							// Not generated
							: 1);
		}
		return map;
	}
	
	
	
	//=======//
	// debug //
	//=======//
	
	@Override
	public void debugRender(DebugRenderer renderer)
	{
		super.debugRender(renderer);
		
		this.delayedFullDataSourceSaveCache.dataSourceByPosition
				.forEach((pos, dataSource) -> { renderer.renderBox(new DebugRenderer.Box(pos, -32f, 80f, 0.20f, Color.green.darker())); });
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
		public Consumer<FullDataSourceV2> getChunkDataConsumer()
		{
			return (chunkSizedFullDataSource) ->
			{
				GeneratedFullDataSourceProvider.this.delayedFullDataSourceSaveCache.queueDataSourceForUpdateAndSave(chunkSizedFullDataSource);
			};
		}
		
	}
	private void onDataSourceSave(FullDataSourceV2 fullDataSource)
	{ GeneratedFullDataSourceProvider.this.updateDataSourceAsync(fullDataSource); }
	
	
	
	/** used by external event listeners */
	@FunctionalInterface
	public interface IOnWorldGenCompleteListener
	{
		/** Fired whenever a section has completed generating */
		void onWorldGenTaskComplete(DhSectionPos pos);
		
	}
	
}