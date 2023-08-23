package com.seibel.distanthorizons.core.util.objects;

import java.util.concurrent.*;

/**
 * Can be used to more finely control CPU usage and
 * reduce CPU usage if only 1 thread is already assigned.
 */
public class RateLimitedThreadPoolExecutor extends ThreadPoolExecutor
{
	public volatile double runTimeRatio;
	
	/** When this thread started running its last task */
	private final ThreadLocal<Long> runStartNanoTimeRef = ThreadLocal.withInitial(() -> -1L);
	/** How long it took this thread to run its last task */
	private final ThreadLocal<Long> lastRunDurationNanoTimeRef = ThreadLocal.withInitial(() -> -1L);
	
	private Runnable onTerminatedEventHandler = null;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public RateLimitedThreadPoolExecutor(int corePoolSize, double runTimeRatio, ThreadFactory threadFactory)
	{
		super(corePoolSize, corePoolSize,
				0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(),
				threadFactory);
		
		this.runTimeRatio = runTimeRatio;
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	protected void beforeExecute(Thread thread, Runnable runnable)
	{
		super.beforeExecute(thread, runnable);
		
		if (this.runTimeRatio < 1.0 && this.lastRunDurationNanoTimeRef.get() != -1)
		{
			try
			{
				long deltaMs = TimeUnit.NANOSECONDS.toMillis(this.lastRunDurationNanoTimeRef.get());
				Thread.sleep((long) (deltaMs / this.runTimeRatio - deltaMs));
			}
			catch (InterruptedException ignored)
			{
			}
		}
		
		this.runStartNanoTimeRef.set(System.nanoTime());
	}
	
	@Override
	protected void afterExecute(Runnable runnable, Throwable throwable)
	{
		super.afterExecute(runnable, throwable);
		this.lastRunDurationNanoTimeRef.set(System.nanoTime() - this.runStartNanoTimeRef.get());
	}
	
	@Override
	protected void terminated() 
	{
		super.terminated();
		if (this.onTerminatedEventHandler != null)
		{
			this.onTerminatedEventHandler.run();
		}
	}
	
	
	
	//==============//
	// custom logic //
	//==============//
	
	/** only one event handler can be present at a time */
	public void setOnTerminatedEventHandler(Runnable runnable) { this.onTerminatedEventHandler = runnable; }
	
}