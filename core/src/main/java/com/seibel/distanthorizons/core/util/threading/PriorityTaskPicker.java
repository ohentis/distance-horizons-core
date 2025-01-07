package com.seibel.distanthorizons.core.util.threading;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.util.objects.RollingAverage;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class PriorityTaskPicker
{
	private final ConfigEntry<Integer> threadCountConfig = Config.Common.MultiThreading.numberOfThreads;
	
	private final RateLimitedThreadPoolExecutor threadPoolExecutor = new RateLimitedThreadPoolExecutor(
			this.threadCountConfig.getMax(),
			new DhThreadFactory("PriorityTaskPicker", Thread.MIN_PRIORITY, false),
			new ArrayBlockingQueue<>(this.threadCountConfig.getMax())
	);
	
	// Queue of executors, used to distribute tasks across executors based on priority
	private final ArrayList<Executor> executorQueue = new ArrayList<>();
	private int nextExecutorQueuePos = 0;
	
	// Lock to ensure task picking logic is thread-safe
	private final ReentrantLock taskPickerLock = new ReentrantLock();
	// Indicates whether a task picking attempt is needed
	private final AtomicBoolean shouldPickTask = new AtomicBoolean(false);
	// Tracks the number of active threads
	private final AtomicInteger occupiedThreads = new AtomicInteger(0);
	
	/**
	 * Creates an executor with a specific priority.
	 * Higher priority executors have more entries in the distribution queue, giving them a greater chance to run tasks.
	 *
	 * @param priority the priority level of the executor
	 * @return a newly created Executor
	 */
	public Executor createExecutor(int priority)
	{
		Executor executor = new Executor();
		
		int entriesToAdd = priority + 1;
		int gapBetweenEntries = (int) (1 / (double) entriesToAdd * this.executorQueue.size());
		
		// Distribute the executor's entries in the queue, ensuring fair distribution
		for (; entriesToAdd > 0; entriesToAdd--)
		{
			this.executorQueue.add(executor);
			Collections.rotate(this.executorQueue, -gapBetweenEntries);
		}
		
		return executor;
	}
	
	/**
	 * Tries to start the next task by iterating over executors in the queue.
	 * Ensures thread limits are respected and only one thread iterates over the executorQueue at a time.
	 */
	private void tryStartNextTask()
	{
		this.shouldPickTask.set(true);
		
		while (this.taskPickerLock.tryLock())
		{
			try
			{
				// Exit if there's no longer a need to pick a task
				if (!this.shouldPickTask.compareAndSet(true, false))
				{
					// There is a small chance for a task to end up in a 'limbo' state,
					// when this.shouldPickTask got set to true right here and this.taskPickerLock is not unlocked yet,
					// but we'll disregard that since tasks get added often enough for this to not be an issue
					
					return;
				}
				
				// Iterate over the executors in the queue, attempting to start tasks
				for (
						int taskPickAttempts = 0;
						taskPickAttempts < this.executorQueue.size() && this.occupiedThreads.get() < this.threadCountConfig.get();
						taskPickAttempts++, this.nextExecutorQueuePos = (this.nextExecutorQueuePos + 1) % this.executorQueue.size()
				)
				{
					Executor executor = this.executorQueue.get(this.nextExecutorQueuePos);
					
					Runnable task = executor.tasks.poll();
					if (task != null)
					{
						// Update variables related to task status
						this.occupiedThreads.getAndIncrement();
						executor.runningTasks.getAndIncrement();
						
						// Prevent exiting early since there might be more than this.executorQueue.size() tasks waiting in queue
						taskPickAttempts = 0;
						
						this.threadPoolExecutor.execute(task);
					}
				}
			}
			finally
			{
				this.taskPickerLock.unlock();
				
				// If someone else manages to pick up a lock before us, we'll leave early, and they will do our work
			}
		}
	}
	
	/**
	 * Shuts down the thread pool immediately, stopping all tasks.
	 */
	public void shutdown()
	{
		this.threadPoolExecutor.shutdownNow();
	}
	
	
	public class Executor extends AbstractExecutorService
	{
		private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
		
		private final AtomicInteger runningTasks = new AtomicInteger(0);
		private final AtomicInteger completedTasks = new AtomicInteger(0);
		private final RollingAverage runTimeInMsRollingAverage = new RollingAverage(200);
		
		
		@Override
		public void execute(@NotNull Runnable command)
		{
			this.tasks.add(() -> {
				long startTime = System.nanoTime();
				try
				{
					command.run();
				}
				finally
				{
					long timeElapsed = System.nanoTime() - startTime;
					this.runTimeInMsRollingAverage.addValue(TimeUnit.NANOSECONDS.toMillis(timeElapsed));
					
					// Update variables related to task status
					PriorityTaskPicker.this.occupiedThreads.getAndDecrement();
					this.runningTasks.getAndDecrement();
					this.completedTasks.getAndIncrement();
					
					// Attempt to start another task
					PriorityTaskPicker.this.tryStartNextTask();
				}
			});
			
			// Attempt to pick up the task immediately
			PriorityTaskPicker.this.tryStartNextTask();
		}
		
		
		public int getQueueSize() { return this.tasks.size(); }
		public int getPoolSize() { return PriorityTaskPicker.this.threadCountConfig.get(); }
		
		public int getRunningTaskCount() { return this.runningTasks.get(); }
		public int getCompletedTaskCount() { return this.completedTasks.get(); }
		/** Will return NaN if nothing has been submitted yet */
		public double getAverageRunTimeInMs() { return this.runTimeInMsRollingAverage.getAverage(); }
		
		
		@Override
		public void shutdown() { throw new UnsupportedOperationException(); }
		
		@Override
		public @NotNull List<Runnable> shutdownNow() { throw new UnsupportedOperationException(); }
		
		@Override
		public boolean isShutdown() { return false; }
		@Override
		public boolean isTerminated() { return false; }
		
		@Override
		public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) { return false; }
		
	}
	
}
