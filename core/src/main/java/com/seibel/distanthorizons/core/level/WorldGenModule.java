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

package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataSourceProvider;
import com.seibel.distanthorizons.core.generation.IFullDataSourceRetrievalQueue;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.world.DhApiWorldProxy;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Handles both single-player/server-side world gen and client side LOD requests.
 * TODO rename
 */
public class WorldGenModule implements Closeable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private final GeneratedFullDataSourceProvider.IOnWorldGenCompleteListener onWorldGenCompleteListener;
	
	private final GeneratedFullDataSourceProvider dataSourceProvider;
	private final Supplier<? extends AbstractWorldGenState> worldGenStateSupplier;
	
	private final AtomicReference<AbstractWorldGenState> worldGenStateRef = new AtomicReference<>();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public WorldGenModule(
			GeneratedFullDataSourceProvider.IOnWorldGenCompleteListener onWorldGenCompleteListener,
			GeneratedFullDataSourceProvider dataSourceProvider,
			Supplier<? extends AbstractWorldGenState> worldGenStateSupplier
		)
	{
		this.onWorldGenCompleteListener = onWorldGenCompleteListener;
		this.dataSourceProvider = dataSourceProvider;
		this.worldGenStateSupplier = worldGenStateSupplier;
	}
	
	
	
	//===================//
	// world gen control //
	//===================//
	
	public void startWorldGen(GeneratedFullDataSourceProvider dataFileHandler, AbstractWorldGenState newWgs)
	{
		// create the new world generator
		if (!this.worldGenStateRef.compareAndSet(null, newWgs))
		{
			LOGGER.warn("Failed to start world gen due to concurrency");
			newWgs.closeAsync(false);
		}
		dataFileHandler.addWorldGenCompleteListener(this.onWorldGenCompleteListener);
		dataFileHandler.setWorldGenerationQueue(newWgs.worldGenerationQueue);
	}
	
	public void stopWorldGen(GeneratedFullDataSourceProvider dataFileHandler)
	{
		AbstractWorldGenState worldGenState = this.worldGenStateRef.get();
		if (worldGenState == null)
		{
			LOGGER.warn("Attempted to stop world gen when it was not running");
			return;
		}
		
		// shut down the world generator
		while (!this.worldGenStateRef.compareAndSet(worldGenState, null))
		{
			worldGenState = this.worldGenStateRef.get();
			if (worldGenState == null)
			{
				return;
			}
		}
		dataFileHandler.clearRetrievalQueue();
		worldGenState.closeAsync(true).join(); //TODO: Make it async.
		dataFileHandler.removeWorldGenCompleteListener(this.onWorldGenCompleteListener);
	}
	
	public void worldGenTick()
	{
		boolean shouldDoWorldGen = this.onWorldGenCompleteListener.shouldDoWorldGen();
		// if the world is read only don't generate anything
		shouldDoWorldGen &= !DhApiWorldProxy.INSTANCE.getReadOnly();
		
		boolean isWorldGenRunning = this.isWorldGenRunning();
		if (shouldDoWorldGen && !isWorldGenRunning)
		{
			// start world gen
			this.startWorldGen(this.dataSourceProvider, this.worldGenStateSupplier.get());
		}
		else if (!shouldDoWorldGen && isWorldGenRunning)
		{
			// stop world gen
			this.stopWorldGen(this.dataSourceProvider);
		}
		
		if (this.isWorldGenRunning())
		{
			AbstractWorldGenState worldGenState = this.worldGenStateRef.get();
			if (worldGenState != null)
			{
				DhBlockPos2D targetPosForGeneration = this.onWorldGenCompleteListener.getTargetPosForGeneration();
				if (targetPosForGeneration != null)
				{
					worldGenState.startGenerationQueueAndSetTargetPos(targetPosForGeneration);
				}
			}
		}
	}
	
	
	
	//=======================//
	// base method overrides //
	//=======================//
	
	@Override
	public void close()
	{
		// shutdown the world-gen
		AbstractWorldGenState worldGenState = this.worldGenStateRef.get();
		if (worldGenState != null)
		{
			while (!this.worldGenStateRef.compareAndSet(worldGenState, null))
			{
				worldGenState = this.worldGenStateRef.get();
				if (worldGenState == null)
				{
					break;
				}
			}
			
			if (worldGenState != null)
			{
				worldGenState.closeAsync(true).join(); //TODO: Make it async.
			}
		}
	}
	
	
	
	//=========//
	// getters //
	//=========//
	
	public boolean isWorldGenRunning() { return this.worldGenStateRef.get() != null; }
	
	/** mutates a list so it can be added to an existing {@link IDhLevel}'s debug list  */
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		AbstractWorldGenState worldGenState = this.worldGenStateRef.get();
		if (worldGenState == null)
		{
			return;
		}
		
		
		String waitingCountStr = F3Screen.NUMBER_FORMAT.format(worldGenState.worldGenerationQueue.getWaitingTaskCount());
		String inProgressCountStr = F3Screen.NUMBER_FORMAT.format(worldGenState.worldGenerationQueue.getInProgressTaskCount());
		String totalCountEstimateStr = F3Screen.NUMBER_FORMAT.format(worldGenState.worldGenerationQueue.getEstimatedTotalTaskCount());
		messageList.add("World Gen Tasks: "+waitingCountStr+"/"+totalCountEstimateStr+" (in progress "+inProgressCountStr+")");
		
		worldGenState.worldGenerationQueue.addDebugMenuStringsToList(messageList);
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	/** Handles the {@link IFullDataSourceRetrievalQueue} and any other necessary world gen information. */
	public static abstract class AbstractWorldGenState
	{
		public IFullDataSourceRetrievalQueue worldGenerationQueue;
		
		CompletableFuture<Void> closeAsync(boolean doInterrupt)
		{
			return this.worldGenerationQueue.startClosingAsync(true, doInterrupt)
				.exceptionally(e ->
				{
					LOGGER.error("Error during first stage of generation queue shutdown, Error: ["+e.getMessage()+"].", e);
					return null;
				}
				).thenRun(this.worldGenerationQueue::close)
				.exceptionally(e ->
				{
					LOGGER.error("Error during second stage of generation queue shutdown, Error: ["+e.getMessage()+"].", e);
					return null;
				});
		}
		
		/** @param targetPosForGeneration the position that world generation should be centered around */
		public void startGenerationQueueAndSetTargetPos(DhBlockPos2D targetPosForGeneration) 
		{ this.worldGenerationQueue.startAndSetTargetPos(targetPosForGeneration); }
	}
	
}
