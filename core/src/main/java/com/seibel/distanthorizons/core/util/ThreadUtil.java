package com.seibel.distanthorizons.core.util;

import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.util.objects.DhThreadFactory;
import com.seibel.distanthorizons.core.util.objects.RateLimitedThreadPoolExecutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.*;

public class ThreadUtil
{
	private static final Logger LOGGER = LogManager.getLogger();
	
	/** The prefix isn't strictly required, but makes debugging and profiling much easier. */
	public static String THREAD_NAME_PREFIX = "DH-";
	
	public static int MINIMUM_RELATIVE_PRIORITY = -4;
	public static int DEFAULT_RELATIVE_PRIORITY = 0;
	
	// TODO currently only used for RateLimitedThreadPools could this be used for all thread pools?
	/** used to track and remove old listeners for certain pools if the thread pool is recreated. */
	private static final ConcurrentHashMap<String, ConfigChangeListener<Double>> THREAD_CHANGE_LISTENERS_BY_THREAD_NAME = new ConcurrentHashMap<>();
	
	
	
	// create rate limited thread pool //
	
	public static RateLimitedThreadPoolExecutor makeRateLimitedThreadPool(int poolSize, String name, ConfigEntry<Double> runTimeRatioConfigEntry) 
	{
		return makeRateLimitedThreadPool(poolSize, name, DEFAULT_RELATIVE_PRIORITY, runTimeRatioConfigEntry); 
	}
	public static RateLimitedThreadPoolExecutor makeRateLimitedThreadPool(int poolSize, String name, int relativePriority, ConfigEntry<Double> runTimeRatioConfigEntry)
	{
		DhThreadFactory threadFactory = new DhThreadFactory(THREAD_NAME_PREFIX + name, Thread.NORM_PRIORITY + relativePriority);
		return makeRateLimitedThreadPool(poolSize, threadFactory, runTimeRatioConfigEntry);
	}
	public static RateLimitedThreadPoolExecutor makeRateLimitedThreadPool(int poolSize, DhThreadFactory threadFactory, ConfigEntry<Double> runTimeRatioConfigEntry)
	{
		// remove the old listener if one exists
		if (THREAD_CHANGE_LISTENERS_BY_THREAD_NAME.containsKey(threadFactory.threadName))
		{
			// note: this assumes only one thread pool exists with a given name
			THREAD_CHANGE_LISTENERS_BY_THREAD_NAME.get(threadFactory.threadName).close();
			THREAD_CHANGE_LISTENERS_BY_THREAD_NAME.remove(threadFactory.threadName);
		}
		
		if (!threadFactory.threadName.startsWith(THREAD_NAME_PREFIX))
		{
			// this will only happen if a ThreadFactory is passed in that doesn't have the correct thread name
			LOGGER.warn("Thread pool with the name ["+threadFactory.threadName+"] is missing the expected Distant Horizons thread prefix ["+THREAD_NAME_PREFIX+"].");
		}
		
		
		RateLimitedThreadPoolExecutor executor = makeRateLimitedThreadPool(poolSize, runTimeRatioConfigEntry.get(), threadFactory);
		
		ConfigChangeListener<Double> changeListener = new ConfigChangeListener<>(runTimeRatioConfigEntry, (newRunTimeRatio) -> { executor.runTimeRatio = newRunTimeRatio; });
		THREAD_CHANGE_LISTENERS_BY_THREAD_NAME.put(threadFactory.threadName, changeListener);
		
		return executor;
	}
	
	
	/** should only be used if there isn't a config controlling the run time ratio of this thread pool */
	public static RateLimitedThreadPoolExecutor makeRateLimitedThreadPool(int poolSize, String name, Double runTimeRatio, int relativePriority) 
	{
		return new RateLimitedThreadPoolExecutor(poolSize, runTimeRatio, new DhThreadFactory(THREAD_NAME_PREFIX + name, Thread.NORM_PRIORITY + relativePriority));
	}
	public static RateLimitedThreadPoolExecutor makeRateLimitedThreadPool(int poolSize, Double runTimeRatio, DhThreadFactory threadFactory) 
	{
		return new RateLimitedThreadPoolExecutor(poolSize, runTimeRatio, threadFactory);
	}
	
	
	// create thread pool // 
	
	public static ThreadPoolExecutor makeThreadPool(int poolSize, String name, int relativePriority)
	{
		// this is what was being internally used by Executors.newFixedThreadPool
		// I'm just calling it explicitly here so we can reference the more feature-rich
		// ThreadPoolExecutor vs the more generic ExecutorService
		return new ThreadPoolExecutor(/*corePoolSize*/ poolSize, /*maxPoolSize*/ poolSize,
				0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<Runnable>(),
				new DhThreadFactory(THREAD_NAME_PREFIX + name, Thread.NORM_PRIORITY + relativePriority));
	}
	
	public static ThreadPoolExecutor makeThreadPool(int poolSize, Class<?> clazz, int relativePriority) { return makeThreadPool(poolSize, clazz.getSimpleName(), relativePriority); }
	public static ThreadPoolExecutor makeThreadPool(int poolSize, String name) { return makeThreadPool(poolSize, name, DEFAULT_RELATIVE_PRIORITY); }
	public static ThreadPoolExecutor makeThreadPool(int poolSize, Class<?> clazz) { return makeThreadPool(poolSize, clazz.getSimpleName(), DEFAULT_RELATIVE_PRIORITY); }
	
	
	// create single thread pool //
	
	public static ThreadPoolExecutor makeSingleThreadPool(String name, int relativePriority) { return makeThreadPool(1, name, relativePriority); }
	public static ThreadPoolExecutor makeSingleThreadPool(Class<?> clazz, int relativePriority) { return makeThreadPool(1, clazz.getSimpleName(), relativePriority); }
	public static ThreadPoolExecutor makeSingleThreadPool(String name) { return makeThreadPool(1, name, DEFAULT_RELATIVE_PRIORITY); }
	public static ThreadPoolExecutor makeSingleThreadPool(Class<?> clazz) { return makeThreadPool(1, clazz.getSimpleName(), DEFAULT_RELATIVE_PRIORITY); }
	
	
}
