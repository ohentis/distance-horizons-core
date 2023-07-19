package com.seibel.distanthorizons.core.dataObjects.transformers;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import org.apache.logging.log4j.LogManager;

public class ChunkToLodBuilder implements AutoCloseable
{
	public static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(), () -> Config.Client.Advanced.Logging.logLodBuilderEvent.get());
	private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	
	private static int threadCount = -1;
	private static ExecutorService executorThreadPool = null;
	private static ConfigChangeListener<Integer> threadConfigListener;
	
	public static final long MAX_TICK_TIME_NS = 1000000000L / 20L;
	/** 
	 * This is done to prevent tasks infinitely piling up if a queued chunk could never be generated, 
	 * But should also prevent de-queuing chunks that should still be generated.
	 */
	public static final short MAX_NUMBER_OF_CHUNK_GENERATION_ATTEMPTS_BEFORE_DISCARDING = 64;
	
	private final ConcurrentHashMap<DhChunkPos, IChunkWrapper> concurrentChunkToBuildByChunkPos = new ConcurrentHashMap<>();
	private final ConcurrentLinkedDeque<Task> concurrentTaskToBuildList = new ConcurrentLinkedDeque<>();
	private final AtomicInteger runningCount = new AtomicInteger(0);
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public ChunkToLodBuilder() { }
	
	
	
	//=================//
	// data generation //
	//=================//
	
	public CompletableFuture<ChunkSizedFullDataAccessor> tryGenerateData(IChunkWrapper chunkWrapper)
	{
		if (chunkWrapper == null)
		{
			throw new NullPointerException("ChunkWrapper cannot be null!");
		}
		
		IChunkWrapper oldChunk = this.concurrentChunkToBuildByChunkPos.put(chunkWrapper.getChunkPos(), chunkWrapper); // an Exchange operation
		// If there's old chunk, that means we just replaced an unprocessed old request on generating data on this pos.
		//   if so, we can just return null to signal this, as the old request's future will instead be the proper one
		//   that will return the latest generated data.
		if (oldChunk != null)
		{
			return null;
		}
		
		// Otherwise, it means we're the first to do so. Let's submit our task to this entry.
		CompletableFuture<ChunkSizedFullDataAccessor> future = new CompletableFuture<>();
		this.concurrentTaskToBuildList.addLast(new Task(chunkWrapper.getChunkPos(), future));
		return future;
	}
	
	public void tick()
	{
		if (this.runningCount.get() >= this.threadCount)
		{
			return;
		}
		else if (this.concurrentTaskToBuildList.isEmpty())
		{
			return;
		}
		else if (MC == null || !MC.playerExists())
		{
			// TODO handle server side properly

			// MC hasn't finished loading (or is currently unloaded)
			
			// can be uncommented if tasks aren't being cleared correctly
			//this.clearCurrentTasks();
			return;
		}
		
		
		for (int i = 0; i< threadCount; i++)
		{
			this.runningCount.incrementAndGet();
			CompletableFuture.runAsync(() ->
			{
				try
				{
					this.tickThreadTask();
				}
				finally
				{
					this.runningCount.decrementAndGet();
				}
			}, executorThreadPool);
		}
	}
	private void tickThreadTask()
	{
		long time = System.nanoTime();
		int count = 0;
		boolean allDone = false;
		while (true)
		{
			// run until we either run out of time, or all tasks are complete
			if (System.nanoTime() - time > MAX_TICK_TIME_NS && !this.concurrentTaskToBuildList.isEmpty())
			{
				break;
			}
			
			Task task = this.concurrentTaskToBuildList.pollFirst();
			if (task == null)
			{
				allDone = true;
				break;
			}
			task.generationAttemptNumber++;
			
			count++;
			IChunkWrapper latestChunk = this.concurrentChunkToBuildByChunkPos.remove(task.chunkPos); // Basically an Exchange operation
			if (latestChunk == null)
			{
				LOGGER.error("Somehow Task at "+task.chunkPos+" has latestChunk as null. Skipping task.");
				task.future.complete(null);
				continue;
			}
			
			try
			{
				if (LodDataBuilder.canGenerateLodFromChunk(latestChunk))
				{
					ChunkSizedFullDataAccessor data = LodDataBuilder.createChunkData(latestChunk);
					if (data != null)
					{
						task.future.complete(data);
						continue;
					}
				}
				else if(task.generationAttemptNumber > MAX_NUMBER_OF_CHUNK_GENERATION_ATTEMPTS_BEFORE_DISCARDING)
				{
					// this task won't be re-queued
					continue;
				}
			}
			catch (Exception ex)
			{
				LOGGER.error("Error while processing Task at "+task.chunkPos, ex);
			}
			
			// Failed to build due to chunk not meeting requirement,
			// re-add it to the queue so it can be tested next time
			IChunkWrapper casChunk = this.concurrentChunkToBuildByChunkPos.putIfAbsent(task.chunkPos, latestChunk); // CAS operation with expected=null
			if (casChunk == null || latestChunk.isStillValid()) // That means CAS have been successful
			{
				this.concurrentTaskToBuildList.addLast(task); // Then add back the same old task.
			}
			else // Else, it means someone managed to sneak in a new gen request in this pos. Then lets drop this old task.
			{
				task.future.complete(null);
			}
			
			count--;
		}
		
		long time2 = System.nanoTime();
		if (!allDone)
		{
			//LOGGER.info("Completed {} tasks in {} in this tick", count, Duration.ofNanos(time2 - time));
		}
		else if (count > 0)
		{
			//LOGGER.info("Completed all {} tasks in {}", count, Duration.ofNanos(time2 - time));
		}
	}
	
	/**
	 * should be called whenever changing levels/worlds 
	 * to prevent trying to generate LODs for chunk(s) that are no longer loaded
	 * (which can cause exceptions)
	 */
	public void clearCurrentTasks()
	{
		this.concurrentTaskToBuildList.clear();
		this.concurrentChunkToBuildByChunkPos.clear();
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
		if (threadConfigListener == null)
		{
			threadConfigListener = new ConfigChangeListener<>(Config.Client.Advanced.MultiThreading.numberOfChunkLodConverterThreads, (threadCount) -> { setThreadPoolSize(threadCount); });
		}
		
		
		if (executorThreadPool == null || executorThreadPool.isTerminated())
		{
			LOGGER.info("Starting "+ChunkToLodBuilder.class.getSimpleName());
			setThreadPoolSize(Config.Client.Advanced.MultiThreading.numberOfChunkLodConverterThreads.get());
		}
	}
	public static void setThreadPoolSize(int threadPoolSize)
	{
		if (executorThreadPool != null && !executorThreadPool.isTerminated())
		{
			executorThreadPool.shutdownNow();
		}
		
		threadCount = threadPoolSize;
		executorThreadPool = ThreadUtil.makeRateLimitedThreadPool(threadPoolSize, ChunkToLodBuilder.class.getSimpleName(), Config.Client.Advanced.MultiThreading.runTimeRatioForChunkLodConverterThreads);
	}
	
	/**
	 * Stops any executing tasks and destroys the executor. <br>
	 * Does nothing if the executor isn't running.
	 */
	public static void shutdownExecutorService()
	{
		if (executorThreadPool != null)
		{
			LOGGER.info("Stopping "+ChunkToLodBuilder.class.getSimpleName());
			executorThreadPool.shutdownNow();
		}
	}
	
	
	
	//==============//
	// base methods //
	//==============//
	
	@Override
	public void close() { this.clearCurrentTasks(); }
	
	
	
	//================//
	// helper classes //
	//================//
	
	private static class Task
	{
		public final DhChunkPos chunkPos;
		public final CompletableFuture<ChunkSizedFullDataAccessor> future;
		/** This is tracked so impossible tasks can be removed from the queue */
		public short generationAttemptNumber = 0;
		
		Task(DhChunkPos chunkPos, CompletableFuture<ChunkSizedFullDataAccessor> future)
		{
			this.chunkPos = chunkPos;
			this.future = future;
		}
	}
	
}

