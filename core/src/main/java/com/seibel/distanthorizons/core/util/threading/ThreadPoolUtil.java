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

import com.seibel.distanthorizons.core.util.ThreadUtil;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.*;

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
	
	private static PriorityTaskPicker taskPicker;
	
	private static PriorityTaskPicker.Executor fileHandlerThreadPool;
	@Nullable
	public static PriorityTaskPicker.Executor getFileHandlerExecutor() { return fileHandlerThreadPool; }
	
	private static PriorityTaskPicker.Executor updatePropagatorThreadPool;
	@Nullable
	public static PriorityTaskPicker.Executor getUpdatePropagatorExecutor() { return updatePropagatorThreadPool; }
	
	public static final DhThreadFactory WORLD_GEN_THREAD_FACTORY = new DhThreadFactory("World Gen", Thread.MIN_PRIORITY, false);
	private static PriorityTaskPicker.Executor worldGenThreadPool;
	@Nullable
	public static PriorityTaskPicker.Executor getWorldGenExecutor() { return worldGenThreadPool; }
	
	public static final String CLEANUP_THREAD_NAME = "Cleanup";
	private static ThreadPoolExecutor cleanupThreadPool;
	@Nullable
	public static ThreadPoolExecutor getCleanupExecutor() { return cleanupThreadPool; }
	
	public static final String BEACON_CULLING_THREAD_NAME = "Beacon Culling";
	private static ThreadPoolExecutor beaconCullingThreadPool;
	@Nullable
	public static ThreadPoolExecutor getBeaconCullingExecutor() { return beaconCullingThreadPool; }
	
	private static PriorityTaskPicker.Executor networkCompressionThreadPool;
	@Nullable
	public static PriorityTaskPicker.Executor getNetworkCompressionExecutor() { return networkCompressionThreadPool; }
	
	
	
	public static final String FULL_DATA_MIGRATION_THREAD_NAME = "Full Data Migration";
	private static ThreadPoolExecutor fullDataMigrationThreadPool;
	@Nullable
	public static ThreadPoolExecutor getFullDataMigrationExecutor() { return fullDataMigrationThreadPool; }
	
	
	private static PriorityTaskPicker.Executor chunkToLodBuilderThreadPool;
	@Nullable
	public static PriorityTaskPicker.Executor getChunkToLodBuilderExecutor() { return chunkToLodBuilderThreadPool; }
	
	
	
	//=================//
	// setup / cleanup //
	//=================//
	
	public static void setupThreadPools()
	{
		// thread pools
		taskPicker = new PriorityTaskPicker();
		networkCompressionThreadPool = taskPicker.createExecutor(3); // Data should never pile up waiting to be sent
		fileHandlerThreadPool = taskPicker.createExecutor(3); // loading in new LODs is second-highest priority
		chunkToLodBuilderThreadPool = taskPicker.createExecutor(2); // We want to make sure any chunk changes are found
		updatePropagatorThreadPool = taskPicker.createExecutor(2); // update propagation needs to be slightly higher priority than world gen
		worldGenThreadPool = taskPicker.createExecutor(1); // higher priorities mean the threads will run first
		
		
		
		// single thread pools
		cleanupThreadPool = ThreadUtil.makeSingleThreadPool(CLEANUP_THREAD_NAME);
		beaconCullingThreadPool = ThreadUtil.makeSingleThreadPool(BEACON_CULLING_THREAD_NAME);
		fullDataMigrationThreadPool = ThreadUtil.makeSingleThreadPool(FULL_DATA_MIGRATION_THREAD_NAME);
		
	}
	
	public static void shutdownThreadPools()
	{
		// standalone threads
		taskPicker.shutdown();
		cleanupThreadPool.shutdown();
		beaconCullingThreadPool.shutdown();
		fullDataMigrationThreadPool.shutdown();
	}
	
}
