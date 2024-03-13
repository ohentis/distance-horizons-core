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

package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.NewFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.file.AbstractNewDataSourceHandler;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.sql.dto.NewFullDataSourceDTO;
import com.seibel.distanthorizons.core.sql.repo.NewFullDataSourceRepo;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class NewFullDataFileHandler 
		extends AbstractNewDataSourceHandler<NewFullDataSource, NewFullDataSourceDTO, NewFullDataSourceRepo, IDhLevel> 
		implements IFullDataSourceProvider, IDebugRenderable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	protected static final int NUMBER_OF_PARENT_UPDATE_TASKS_PER_THREAD = 50;
	/** how many parent update tasks can be in the queue at once */
	protected static final int MAX_UPDATE_TASK_COUNT = NUMBER_OF_PARENT_UPDATE_TASKS_PER_THREAD * Config.Client.Advanced.MultiThreading.numberOfFileHandlerThreads.get();
	
	/** indicates how long the update queue thread should wait between queuing ticks */
	protected static final int UPDATE_QUEUE_THREAD_DELAY_IN_MS = 250;
	
	/** how many data sources should be pulled down for migration at once */
	private static final int MIGRATION_BATCH_COUNT = NUMBER_OF_PARENT_UPDATE_TASKS_PER_THREAD;
	private static final String MIGRATION_THREAD_NAME_PREFIX = "Full Data Migration Thread: ";
	/** 1 minute */
	private static final int MIGRATION_MAX_UPDATE_TIMEOUT_IN_MS = 60 * 1_000;
	
	
	protected final ThreadPoolExecutor migrationThreadPool;
	/** 
	 * Interrupting the migration thread pool doesn't work well and may corrupt the database
	 * vs gracefully shutting down the thread ourselves. 
	 */
	protected final AtomicBoolean migrationThreadRunning = new AtomicBoolean(true);
	protected final LegacyFullDataFileHandler legacyFileHandler;
	
	/** 
	 * Tracks which positions are currently being updated
	 * to prevent duplicate concurrent updates.
	 */
	public final Set<DhSectionPos> parentUpdatingPosSet = ConcurrentHashMap.newKeySet();
	
	// TODO only run thread if modifications happened recently
	/** 
	 * This isn't in {@link AbstractNewDataSourceHandler} since we don't need parent updating logic
	 * for render data, only full data.
	 */
	private final ThreadPoolExecutor updateQueueProcessor;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public NewFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure) { this(level, saveStructure, null); }
	public NewFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure, @Nullable File saveDirOverride) 
	{
		super(level, saveStructure, saveDirOverride);
		this.legacyFileHandler = new LegacyFullDataFileHandler(level, saveStructure, saveDirOverride);
		
		DebugRenderer.register(this, Config.Client.Advanced.Debugging.DebugWireframe.showFullDataUpdateStatus);
		
		String dimensionName = level.getLevelWrapper().getDimensionType().getDimensionName();
		
		// start migrating any legacy data sources present in the background
		int totalCount = this.legacyFileHandler.getDataSourceMigrationCount();
		LOGGER.info("Found ["+totalCount+"] data sources that need migration.");
		this.migrationThreadPool = ThreadUtil.makeRateLimitedThreadPool(1, MIGRATION_THREAD_NAME_PREFIX +"["+dimensionName+"]", Config.Client.Advanced.MultiThreading.runTimeRatioForUpdatePropagatorThreads.get(), Thread.MIN_PRIORITY, (Semaphore)null);
		this.migrationThreadPool.execute(() -> this.convertLegacyDataSources());
		
		this.updateQueueProcessor = ThreadUtil.makeSingleThreadPool("Parent Update Queue ["+dimensionName+"]");
		this.updateQueueProcessor.execute(() -> this.runUpdateQueue());
	}
	
	
	
	//====================//
	// Abstract overrides //
	//====================//
	
	@Override
	protected NewFullDataSourceRepo createRepo()
	{
		try
		{
			return new NewFullDataSourceRepo("jdbc:sqlite", this.saveDir.getPath() + "/" + AbstractSaveStructure.DATABASE_NAME);
		}
		catch (SQLException e)
		{
			// should only happen if there is an issue with the database (it's locked or the folder path is missing) 
			// or the database update failed
			throw new RuntimeException(e);
		}
	}
	
	@Override 
	protected NewFullDataSourceDTO createDtoFromDataSource(NewFullDataSource dataSource)
	{
		try
		{
			return NewFullDataSourceDTO.CreateFromDataSource(dataSource);
		}
		catch (IOException e)
		{
			LOGGER.warn("Unable to create DTO, error: "+e.getMessage(), e);
			return null;
		}
	}
	
	@Override
	protected NewFullDataSource createDataSourceFromDto(NewFullDataSourceDTO dto) throws InterruptedException, IOException
	{ return dto.createDataSource(this.level.getLevelWrapper()); }
	@Override
	protected NewFullDataSource createNewDataSourceFromExistingDtos(DhSectionPos pos)
	{
		//  TODO maybe just set children update flags to true?
		return NewFullDataSource.createEmpty(pos);
	}
	
	@Override
	protected NewFullDataSource makeEmptyDataSource(DhSectionPos pos) { return NewFullDataSource.createEmpty(pos); }
	
	@Override
	public boolean canQueueRetrieval()
	{
		// Retrieval shouldn't happen while an unknown number of
		// legacy data sources are present.
		// If retrieval was allowed we might run into concurrency issues.
		return !this.migrationThreadRunning.get();
	}
	
	
	
	
	//================//
	// parent updates //
	//================//
	
	private void runUpdateQueue()
	{
		while (!Thread.interrupted())
		{
			try
			{
				Thread.sleep(UPDATE_QUEUE_THREAD_DELAY_IN_MS);
				
				ThreadPoolExecutor executor = ThreadPoolUtil.getUpdatePropagatorExecutor();
				if (executor == null || executor.isTerminated())
				{
					continue;
				}
				
				
				
				// queue parent updates
				if (executor.getQueue().size() < MAX_UPDATE_TASK_COUNT
					&& this.parentUpdatingPosSet.size() < MAX_UPDATE_TASK_COUNT)
				{
					// get the positions that need to be applied to their parents
					ArrayList<DhSectionPos> parentUpdatePosList = this.repo.getPositionsToUpdate(MAX_UPDATE_TASK_COUNT);
					
					// combine updates together based on their parent
					HashMap<DhSectionPos, HashSet<DhSectionPos>> updatePosByParentPos = new HashMap<>();
					for (DhSectionPos pos : parentUpdatePosList)
					{
						updatePosByParentPos.compute(pos.getParentPos(), (parentPos, updatePosSet) -> 
						{
							if (updatePosSet == null)
							{
								updatePosSet = new HashSet<>();
							}
							updatePosSet.add(pos);
							return updatePosSet;
						});
					}
					
					// queue the updates
					for (DhSectionPos parentUpdatePos : updatePosByParentPos.keySet())
					{
						// stop if there are already a bunch of updates queued
						if (this.parentUpdatingPosSet.size() > MAX_UPDATE_TASK_COUNT
							|| !this.parentUpdatingPosSet.add(parentUpdatePos))
						{
							break;
						}
						
						try
						{
							executor.execute(() ->
							{
								ReentrantLock parentWriteLock = this.updateLockProvider.getLock(parentUpdatePos);
								boolean parentLocked = false;
								try
								{
									//LOGGER.info("updating parent: "+parentUpdatePos);
									
									// Locking the parent before the children should prevent deadlocks.
									// TryLock is used instead of lock so this thread can handle a different update.
									if (parentWriteLock.tryLock())
									{
										parentLocked = true;
										this.lockedPosSet.add(parentUpdatePos);
										
										// apply each child pos to the parent
										for (DhSectionPos childPos : updatePosByParentPos.get(parentUpdatePos))
										{
											ReentrantLock childReadLock = this.updateLockProvider.getLock(childPos);
											try
											{
												childReadLock.lock();
												this.lockedPosSet.add(childPos);
												
												NewFullDataSource dataSource = this.get(childPos);
												this.updateDataSourceAtPos(parentUpdatePos, dataSource, false);
												this.repo.setApplyToParent(childPos, false);
											}
											catch (Exception e)
											{
												LOGGER.error("issue in update for parent pos: " + parentUpdatePos);
											}
											finally
											{
												childReadLock.unlock();
												this.lockedPosSet.remove(childPos);
											}
										}
									}
								}
								finally
								{
									if (parentLocked)
									{
										parentWriteLock.unlock();
										this.lockedPosSet.remove(parentUpdatePos);
									}
									
									this.parentUpdatingPosSet.remove(parentUpdatePos);
								}
							});
						}
						catch (Exception e)
						{
							this.parentUpdatingPosSet.remove(parentUpdatePos);
							throw e;
						}
					}
				}
				
			}
			catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
			catch (Exception e)
			{
				LOGGER.error("Unexpected error in the parent update queue thread. Error: " + e.getMessage(), e);
			}
		}
		
		LOGGER.info("Update thread ["+Thread.currentThread().getName()+"] terminated.");
	}
	
	
	
	//=======================//
	// data source migration //
	//=======================//
	
	private void convertLegacyDataSources()
	{
		String dimensionName = this.level.getLevelWrapper().getDimensionType().getDimensionName();
		LOGGER.info("Attempting to migrate data sources for: ["+dimensionName+"]-["+this.saveDir+"]...");
		
		int totalCount = this.legacyFileHandler.getDataSourceMigrationCount();
		LOGGER.info("Found ["+totalCount+"] data sources that need migration.");
		
		
		ArrayList<CompleteFullDataSource> legacyDataSourceList = this.legacyFileHandler.getDataSourcesToMigrate(MIGRATION_BATCH_COUNT);
		if (!legacyDataSourceList.isEmpty())
		{
			// keep going until every data source has been migrated
			int progressCount = 0;
			while (!legacyDataSourceList.isEmpty() && this.migrationThreadRunning.get())
			{
				LOGGER.info("Migrating ["+dimensionName+"] - [" + progressCount + "/" + totalCount + "]...");
				
				ArrayList<CompletableFuture<Void>> updateFutureList = new ArrayList<>();
				for (int i = 0; i < legacyDataSourceList.size() && this.migrationThreadRunning.get(); i++)
				{
					CompleteFullDataSource legacyDataSource = legacyDataSourceList.get(i);
					
					try
					{
						// convert the legacy data source to the new format,
						// this is a relatively cheap operation
						NewFullDataSource newDataSource = NewFullDataSource.createFromCompleteDataSource(legacyDataSource);
						newDataSource.applyToParent = true;
						
						// the actual update process can be moderately expensive due to having to update
						// the render data along with the full data, so running it async on the update threads gains us a good bit of speed
						CompletableFuture<Void> future = this.updateDataSourceAsync(newDataSource);
						updateFutureList.add(future);
						future.thenRun(() -> 
						{
							// after the update finishes the legacy data source can be safely deleted
							this.legacyFileHandler.repo.deleteWithKey(legacyDataSource.getSectionPos());
						});
					}
					catch (Exception e)
					{
						DhSectionPos migrationPos = legacyDataSource.getSectionPos();
						LOGGER.error("Unexpected issue migrating data source at pos "+migrationPos+". Error: "+e.getMessage(), e);
						this.legacyFileHandler.markMigrationFailed(migrationPos);
					}
				}
				
				
				try
				{
					// wait for each thread to finish updating
					CompletableFuture<Void> combinedFutures = CompletableFuture.allOf(updateFutureList.toArray(new CompletableFuture[0]));
					combinedFutures.get(MIGRATION_MAX_UPDATE_TIMEOUT_IN_MS, TimeUnit.MILLISECONDS);
				}
				catch (InterruptedException | ExecutionException | TimeoutException e) 
				{
					LOGGER.warn("Migration update timed out. Migration will re-try the same positions again. Error:"+e.getMessage(), e);
				}
				
				legacyDataSourceList = this.legacyFileHandler.getDataSourcesToMigrate(MIGRATION_BATCH_COUNT);
				progressCount += legacyDataSourceList.size();
			}
			
			
			if (this.migrationThreadRunning.get())
			{
				LOGGER.info("migration complete for: ["+dimensionName+"]-["+this.saveDir+"].");
			}
			else
			{
				LOGGER.info("migration stopped for: ["+dimensionName+"]-["+this.saveDir+"].");
			}
		}
		else
		{
			LOGGER.info("No migration necessary.");
		}
		
		this.migrationThreadRunning.set(false);
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override
	public void debugRender(DebugRenderer renderer)
	{
		this.lockedPosSet
				.forEach((pos) -> { renderer.renderBox(new DebugRenderer.Box(pos, -32f, 74f, 0.15f, Color.PINK)); });
		
		this.queuedUpdateCountsByPos
				.forEach((pos, updateCountRef) -> { renderer.renderBox(new DebugRenderer.Box(pos, -32f, 80f + (updateCountRef.get() * 16f), 0.20f, Color.WHITE)); });
		this.parentUpdatingPosSet
				.forEach((pos) -> { renderer.renderBox(new DebugRenderer.Box(pos, -32f, 80f, 0.20f, Color.MAGENTA)); });
	}
	
	@Override 
	public void close()
	{
		super.close();
		this.updateQueueProcessor.shutdownNow();
		
		this.migrationThreadRunning.set(false);
		this.migrationThreadPool.shutdown();
	}
	
}
