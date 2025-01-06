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
	
	private final ArrayList<Executor> executorQueue = new ArrayList<>();
	private int nextExecutorQueuePos = 0;
	
	private final ReentrantLock taskPickerLock = new ReentrantLock();
	private final AtomicBoolean shouldPickTask = new AtomicBoolean(false);
	private final AtomicInteger occupiedThreads = new AtomicInteger(0);
	
	
	public Executor createExecutor(int priority)
	{
		Executor executor = new Executor();
		
		int entriesToAdd = priority + 1;
		int gapBetweenEntries = (int) (1 / (double) entriesToAdd * this.executorQueue.size());
		
		for (; entriesToAdd > 0; entriesToAdd--)
		{
			this.executorQueue.add(executor);
			Collections.rotate(this.executorQueue, -gapBetweenEntries);
		}
		
		return executor;
	}
	
	private void tryStartNextTask()
	{
		this.shouldPickTask.set(true);
		
		while (this.taskPickerLock.tryLock())
		{
			try
			{
				if (!this.shouldPickTask.compareAndSet(true, false))
				{
					return;
				}
				
				int threadCount = this.threadCountConfig.get();
				
				for (
						int counter = 0;
						counter < this.executorQueue.size() && this.occupiedThreads.get() < threadCount;
						counter++, this.nextExecutorQueuePos = (this.nextExecutorQueuePos + 1) % this.executorQueue.size()
				)
				{
					Executor executor = this.executorQueue.get(this.nextExecutorQueuePos);
					
					Runnable task = executor.tasks.poll();
					if (task != null)
					{
						this.occupiedThreads.getAndIncrement();
						executor.runningTasks.getAndIncrement();
						counter--;
						
						this.threadPoolExecutor.execute(task);
					}
				}
			}
			finally
			{
				this.taskPickerLock.unlock();
			}
		}
	}
	
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
					
					PriorityTaskPicker.this.occupiedThreads.getAndDecrement();
					this.runningTasks.getAndDecrement();
					this.completedTasks.getAndIncrement();
					
					PriorityTaskPicker.this.tryStartNextTask();
				}
			});
			
			PriorityTaskPicker.this.tryStartNextTask();
		}
		
		public int getQueueSize() { return this.tasks.size(); }
		public int getPoolSize() { return PriorityTaskPicker.this.threadCountConfig.get(); }
		
		public int getRunningTaskCount() { return this.runningTasks.get(); }
		public int getCompletedTaskCount() { return this.completedTasks.get(); }
		/** will return Nan if nothing has been submitted yet */
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
