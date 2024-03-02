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
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.NewFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.generation.IWorldGenerationQueue;
import com.seibel.distanthorizons.core.generation.tasks.IWorldGenTaskTracker;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenResult;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

public class NewGeneratedFullDataFileHandler extends NewFullDataFileHandler
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private final AtomicReference<IWorldGenerationQueue> worldGenQueueRef = new AtomicReference<>(null);
	
	private final ArrayList<IOnWorldGenCompleteListener> onWorldGenTaskCompleteListeners = new ArrayList<>();
	
	
	// TODO name better
	//  this is just the list of section pos that have had their world generation
	//  calculated and queued this session.
	private final Set<DhSectionPos> genHandledPosSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public NewGeneratedFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure) { super(level, saveStructure); }
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override
	public NewFullDataSource get(DhSectionPos pos) { return this.get(pos, true); }
	public NewFullDataSource get(DhSectionPos pos, boolean runWorldGenCheck)
	{
		NewFullDataSource dataSource = super.get(pos);
		
		if (runWorldGenCheck)
		{
			this.tryQueueSection(pos);
		}
		
		return dataSource;
	}
	
	@Override
	public void queuePositionForGenerationOrRetrievalIfNecessary(DhSectionPos pos) { this.tryQueueSection(pos); }
	
	
	
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
		this.genHandledPosSet.clear();
	}
	
	/** Can be used to remove positions that are outside the player's render distance. */
	public void removeGenRequestIf(Function<DhSectionPos, Boolean> removeIf)
	{
		// TODO there has to be a better way to do this
		
		IWorldGenerationQueue worldGenQueue = this.worldGenQueueRef.get();
		if (worldGenQueue != null)
		{
			worldGenQueue.removeGenRequestIf(removeIf);
		}
		
		this.genHandledPosSet.forEach((pos) ->
		{
			if (removeIf.apply(pos))
			{
				this.genHandledPosSet.remove(pos);
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
	
	// TODO only fire after the section has finished generated or once every X seconds
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
	
	/** does nothing if this section or one of it's parents has already been queued */
	public void tryQueueSection(DhSectionPos pos)
	{
		IWorldGenerationQueue tempWorldGenQueue = this.worldGenQueueRef.get();
		if (tempWorldGenQueue == null)
		{
			return;
		}
		
		if (this.genHandledPosSet.contains(pos))
		{
			return;
		}
		
		
		
		AtomicBoolean positionAlreadyHandled = new AtomicBoolean(false);
		pos.forEachPosUpToDetailLevel(NewFullDataFileHandler.TOP_SECTION_DETAIL_LEVEL, (parentPos) ->
		{
			if (!positionAlreadyHandled.get())
			{
				if (this.genHandledPosSet.contains(parentPos))
				{
					positionAlreadyHandled.set(true);
				}
			}
		});
		
		if (positionAlreadyHandled.get())
		{
			return;
		}
		this.genHandledPosSet.add(pos);
		
		
		
		// get the un-generated pos list
		byte minGeneratorSectionDetailLevel = (byte) (tempWorldGenQueue.highestDataDetail() + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		
		pos.forEachChildAtDetailLevel(minGeneratorSectionDetailLevel, (genPos) ->
		{
			IWorldGenerationQueue worldGenQueue = this.worldGenQueueRef.get();
			if (worldGenQueue == null)
			{
				return;
			}
			
			if (this.repo.existsWithKey(genPos))
			{
				// TODO only pull in the generation steps
				NewFullDataSource potentialDataSource = this.get(genPos, false);
				
				EDhApiWorldGenerationStep currentMinWorldGenStep = EDhApiWorldGenerationStep.LIGHT;
				checkWorldGenLoop:
				for (int x = 0; x < NewFullDataSource.WIDTH; x++)
				{
					for (int z = 0; z < NewFullDataSource.WIDTH; z++)
					{
						int index = NewFullDataSource.relativePosToIndex(x, z);
						byte genStepValue = potentialDataSource.columnGenerationSteps[index];
						
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
					// no world gen needed
					return;
				}
			}
			
			
			// queue each new gen task
			GenTask genTask = new GenTask(genPos);
			CompletableFuture<WorldGenResult> worldGenFuture = tempWorldGenQueue.submitGenTask(genPos, (byte) (genPos.getDetailLevel() - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL), genTask);
			worldGenFuture.whenComplete((genTaskResult, ex) -> this.onWorldGenTaskComplete(genTaskResult, ex));
		});
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
		public Consumer<NewFullDataSource> getChunkDataConsumer()
		{
			return (chunkSizedFullDataSource) ->
			{
				NewGeneratedFullDataFileHandler.this.level.updateDataSources(chunkSizedFullDataSource);
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
