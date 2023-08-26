package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataFileHandler;
import com.seibel.distanthorizons.core.generation.IWorldGenerationQueue;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import org.apache.logging.log4j.Logger;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class WorldGenModule implements Closeable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private final GeneratedFullDataFileHandler dataFileHandler;
	private final GeneratedFullDataFileHandler.IOnWorldGenCompleteListener onWorldGenCompleteListener;
	
	private final AtomicReference<AbstractWorldGenState> worldGenStateRef = new AtomicReference<>();
	private final F3Screen.DynamicMessage worldGenF3Message;
	
	
	
	public WorldGenModule(GeneratedFullDataFileHandler dataFileHandler, GeneratedFullDataFileHandler.IOnWorldGenCompleteListener onWorldGenCompleteListener)
	{
		this.dataFileHandler = dataFileHandler;
		this.onWorldGenCompleteListener = onWorldGenCompleteListener;
		this.worldGenF3Message = new F3Screen.DynamicMessage(() ->
		{
			AbstractWorldGenState worldGenState = this.worldGenStateRef.get();
			if (worldGenState != null)
			{
				int waitingCount = worldGenState.worldGenerationQueue.getWaitingTaskCount();
				int inProgressCount = worldGenState.worldGenerationQueue.getInProgressTaskCount();
				
				return "World Gen Tasks: "+waitingCount+", (in progress: "+inProgressCount+")";
			}
			else
			{
				return "World Gen Disabled";
			}
		});
		
	}
	
	
	
	//===================//
	// world gen control //
	//===================//
	
	public void startWorldGen(GeneratedFullDataFileHandler dataFileHandler, AbstractWorldGenState newWgs)
	{
		// create the new world generator
		if (!this.worldGenStateRef.compareAndSet(null, newWgs))
		{
			LOGGER.warn("Failed to start world gen due to concurrency");
			newWgs.closeAsync(false);
		}
		dataFileHandler.addWorldGenCompleteListener(this.onWorldGenCompleteListener);
		dataFileHandler.setGenerationQueue(newWgs.worldGenerationQueue);
	}
	
	public void stopWorldGen(GeneratedFullDataFileHandler dataFileHandler)
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
		dataFileHandler.clearGenerationQueue();
		worldGenState.closeAsync(true).join(); //TODO: Make it async.
		dataFileHandler.removeWorldGenCompleteListener(this.onWorldGenCompleteListener);
	}
	
	/** @param targetPosForGeneration the position that world generation should be centered around */
	public void worldGenTick(DhBlockPos2D targetPosForGeneration)
	{
		AbstractWorldGenState worldGenState = this.worldGenStateRef.get();
		if (worldGenState != null)
		{
			// queue new world generation requests
			worldGenState.tick(targetPosForGeneration);
		}
	}
	
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
		
		this.dataFileHandler.close();
		this.worldGenF3Message.close();
	}
	
	
	
	//=========//
	// getters //
	//=========//
	
	public boolean isWorldGenRunning() { return this.worldGenStateRef.get() != null; }
	
	
	
	//================//
	// helper classes //
	//================//
	
	/** Handles the {@link IWorldGenerationQueue} and any other necessary world gen information. */
	public static abstract class AbstractWorldGenState
	{
		public IWorldGenerationQueue worldGenerationQueue;
		
		CompletableFuture<Void> closeAsync(boolean doInterrupt)
		{
			return this.worldGenerationQueue.startClosing(true, doInterrupt)
					.exceptionally(ex ->
							{
								LOGGER.error("Error closing generation queue", ex);
								return null;
							}
					).thenRun(this.worldGenerationQueue::close)
					.exceptionally(ex ->
					{
						LOGGER.error("Error closing world gen", ex);
						return null;
					});
		}
		
		/** @param targetPosForGeneration the position that world generation should be centered around */
		public void tick(DhBlockPos2D targetPosForGeneration) { this.worldGenerationQueue.runCurrentGenTasksUntilBusy(targetPosForGeneration); }
	}
	
}
