package com.seibel.distanthorizons.core.util;

import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.util.objects.DhThreadFactory;
import com.seibel.distanthorizons.core.util.objects.RateLimitedThreadPoolExecutor;

import java.util.concurrent.*;

public class ThreadUtil
{
	public static int MINIMUM_RELATIVE_PRIORITY = -5;
	public static int DEFAULT_RELATIVE_PRIORITY = 0;
	
	// TODO currently only used for RateLimitedThreadPools could this be used for all thread pools?
	/** used to track and remove old listeners for certain pools if the thread pool is recreated. */
	private static final ConcurrentHashMap<String, ConfigChangeListener<Double>> THREAD_CHANGE_LISTENERS_BY_THREAD_NAME = new ConcurrentHashMap<>();
	
	
	
	// create rate limited thread pool //
	
	public static RateLimitedThreadPoolExecutor makeRateLimitedThreadPool(int poolSize, String name, ConfigEntry<Double> runTimeRatioConfigEntry) { return makeRateLimitedThreadPool(poolSize, name, 0, runTimeRatioConfigEntry); }
	public static RateLimitedThreadPoolExecutor makeRateLimitedThreadPool(int poolSize, String name, int relativePriority, ConfigEntry<Double> runTimeRatioConfigEntry)
	{
		// remove the old listener if one exists
		if (THREAD_CHANGE_LISTENERS_BY_THREAD_NAME.containsKey(name))
		{
			// note: this assumes only one thread pool exists with a given name
			THREAD_CHANGE_LISTENERS_BY_THREAD_NAME.get(name).close();
			THREAD_CHANGE_LISTENERS_BY_THREAD_NAME.remove(name);
		}
		
		RateLimitedThreadPoolExecutor executor = new RateLimitedThreadPoolExecutor(poolSize, runTimeRatioConfigEntry.get(), new DhThreadFactory("DH-" + name, Thread.NORM_PRIORITY+relativePriority));
		
		ConfigChangeListener<Double> changeListener = new ConfigChangeListener<>(runTimeRatioConfigEntry, (newRunTimeRatio) -> { executor.runTimeRatio = newRunTimeRatio; });
		THREAD_CHANGE_LISTENERS_BY_THREAD_NAME.put(name, changeListener);
		
		return executor;
	}
	
	
	// create thread pool // 
	
	public static ThreadPoolExecutor makeThreadPool(int poolSize, String name, int relativePriority)
	{
		// this is what was being internally used by Executors.newFixedThreadPool
		// I'm just calling it explicitly here so we can reference the more feature-rich
		// ThreadPoolExecutor vs the more generic ExecutorService
		return new ThreadPoolExecutor(/*corePoolSize*/poolSize, /*maxPoolSize*/poolSize,
				0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<Runnable>(),
				new DhThreadFactory("DH-" + name, Thread.NORM_PRIORITY+relativePriority));
	}
	
	public static ThreadPoolExecutor makeThreadPool(int poolSize, Class<?> clazz, int relativePriority) { return makeThreadPool(poolSize, clazz.getSimpleName(), relativePriority); }
	public static ThreadPoolExecutor makeThreadPool(int poolSize, String name) { return makeThreadPool(poolSize, name, 0); }
	public static ThreadPoolExecutor makeThreadPool(int poolSize, Class<?> clazz) { return makeThreadPool(poolSize, clazz.getSimpleName(), 0); }
	
	
	// create single thread pool //
	
	public static ThreadPoolExecutor makeSingleThreadPool(String name, int relativePriority) { return makeThreadPool(1, name, relativePriority); }
	public static ThreadPoolExecutor makeSingleThreadPool(Class<?> clazz, int relativePriority) { return makeThreadPool(1, clazz.getSimpleName(), relativePriority); }
	public static ThreadPoolExecutor makeSingleThreadPool(String name) { return makeThreadPool(1, name, 0); }
	public static ThreadPoolExecutor makeSingleThreadPool(Class<?> clazz) { return makeThreadPool(1, clazz.getSimpleName(), 0); }
	
	
}
