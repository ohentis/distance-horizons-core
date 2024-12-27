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

package com.seibel.distanthorizons.core.util.threading;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Holds each thread pool the system uses.
 * 
 * @see ThreadUtil
 */
public class ThreadPoolUtil
{
	//=========================//
	// standalone thread pools //
	//=========================//
	
	// standalone thread pools all handle independent systems
	// and don't interfere with any other pool
	
	public static final DhThreadFactory FILE_HANDLER_THREAD_FACTORY = new DhThreadFactory("File Handler", Thread.MIN_PRIORITY, false);
	private static ConfigThreadPool fileHandlerThreadPool;
	@Nullable
	public static ThreadPoolExecutor getFileHandlerExecutor() { return fileHandlerThreadPool.executor; }
	
	public static final DhThreadFactory UPDATE_PROPAGATOR_THREAD_FACTORY = new DhThreadFactory("LOD Update Propagator", Thread.MIN_PRIORITY, false);
	private static ConfigThreadPool updatePropagatorThreadPool;
	@Nullable
	public static ThreadPoolExecutor getUpdatePropagatorExecutor() { return updatePropagatorThreadPool.executor; }
	
	public static final DhThreadFactory WORLD_GEN_THREAD_FACTORY = new DhThreadFactory("World Gen", Thread.MIN_PRIORITY, false);
	private static ConfigThreadPool worldGenThreadPool;
	@Nullable
	public static ThreadPoolExecutor getWorldGenExecutor() { return worldGenThreadPool.executor; }
	
	public static final String CLEANUP_THREAD_NAME = "Cleanup";
	private static ThreadPoolExecutor cleanupThreadPool;
	@Nullable
	public static ThreadPoolExecutor getCleanupExecutor() { return cleanupThreadPool; }
	
	public static final String BEACON_CULLING_THREAD_NAME = "Beacon Culling";
	private static ThreadPoolExecutor beaconCullingThreadPool;
	@Nullable
	public static ThreadPoolExecutor getBeaconCullingExecutor() { return beaconCullingThreadPool; }
	
	public static final DhThreadFactory NETWORK_COMPRESSION_THREAD_FACTORY = new DhThreadFactory("Network Compression", Thread.MIN_PRIORITY, false);
	private static ConfigThreadPool networkCompressionThreadPool;
	@Nullable
	public static ThreadPoolExecutor getNetworkCompressionExecutor() { return networkCompressionThreadPool.executor; }
	
	
	
	public static final String FULL_DATA_MIGRATION_THREAD_NAME = "Full Data Migration";
	private static ThreadPoolExecutor fullDataMigrationThreadPool;
	@Nullable
	public static ThreadPoolExecutor getFullDataMigrationExecutor() { return fullDataMigrationThreadPool; }
	
	
	public static final DhThreadFactory CHUNK_TO_LOD_BUILDER_THREAD_FACTORY = new DhThreadFactory("LOD Builder - Chunk to Lod Builder", Thread.MIN_PRIORITY, false);
	private static ConfigThreadPool chunkToLodBuilderThreadPool;
	@Nullable
	public static ThreadPoolExecutor getChunkToLodBuilderExecutor() { return (chunkToLodBuilderThreadPool != null) ? chunkToLodBuilderThreadPool.executor : null; }
	
	
	
	/** how many total threads can be used */
	private static int threadSemaphoreCount = Config.Common.MultiThreading.numberOfThreads.get();
	public static int getThreadCount() { return threadSemaphoreCount; }
	private static PrioritySemaphore threadSemaphore = null;
	private static ConfigChangeListener<Integer> threadSemaphoreConfigListener = null;
	
	
	
	//=================//
	// setup / cleanup //
	//=================//
	
	public static void setupThreadPools()
	{
		// create thread semaphore
		threadSemaphoreCount = Config.Common.MultiThreading.numberOfThreads.get();
		threadSemaphore = new PrioritySemaphore(threadSemaphoreCount);
		threadSemaphoreConfigListener = new ConfigChangeListener<>(Config.Common.MultiThreading.numberOfThreads, (val) ->
		{
			threadSemaphore.changePermitCount(val);
			threadSemaphoreCount = val;
		});
		
		
		
		// thread pools
		chunkToLodBuilderThreadPool = new ConfigThreadPool(CHUNK_TO_LOD_BUILDER_THREAD_FACTORY,
				Config.Common.MultiThreading.numberOfThreads, Config.Common.MultiThreading.threadRunTimeRatio,
				threadSemaphore, 3); // We want to make sure any chunk changes are found
		fileHandlerThreadPool = new ConfigThreadPool(FILE_HANDLER_THREAD_FACTORY,
				Config.Common.MultiThreading.numberOfThreads, Config.Common.MultiThreading.threadRunTimeRatio,
				threadSemaphore, 2); // loading in new LODs is second highest priority
		updatePropagatorThreadPool = new ConfigThreadPool(UPDATE_PROPAGATOR_THREAD_FACTORY,
				Config.Common.MultiThreading.numberOfThreads, Config.Common.MultiThreading.threadRunTimeRatio,
				threadSemaphore, 1); // update propagation needs to be slightly higher priority than world gen
		worldGenThreadPool = new ConfigThreadPool(WORLD_GEN_THREAD_FACTORY, 
				Config.Common.MultiThreading.numberOfThreads, Config.Common.MultiThreading.threadRunTimeRatio,
				threadSemaphore, 0); // higher priorities mean the threads will run first
		networkCompressionThreadPool = new ConfigThreadPool(NETWORK_COMPRESSION_THREAD_FACTORY, 
				Config.Common.MultiThreading.numberOfThreads, Config.Common.MultiThreading.threadRunTimeRatio,
				threadSemaphore, 0); // networking can probably have similar priority to world gen since they work similarly
		
		
		
		// single thread pools
		cleanupThreadPool = ThreadUtil.makeSingleThreadPool(CLEANUP_THREAD_NAME);
		beaconCullingThreadPool = ThreadUtil.makeSingleThreadPool(BEACON_CULLING_THREAD_NAME);
		fullDataMigrationThreadPool = ThreadUtil.makeSingleThreadPool(FULL_DATA_MIGRATION_THREAD_NAME);
		
	}
	
	public static void shutdownThreadPools()
	{
		// standalone threads
		fileHandlerThreadPool.shutdownExecutorService();
		updatePropagatorThreadPool.shutdownExecutorService();
		worldGenThreadPool.shutdownExecutorService();
		networkCompressionThreadPool.shutdownExecutorService();
		cleanupThreadPool.shutdown();
		beaconCullingThreadPool.shutdown();
		fullDataMigrationThreadPool.shutdown();
		chunkToLodBuilderThreadPool.shutdownExecutorService();
		
		threadSemaphore = null;
		
		if (threadSemaphoreConfigListener != null)
		{
			threadSemaphoreConfigListener.close();
			threadSemaphoreConfigListener = null;
		}
	}
	
}
