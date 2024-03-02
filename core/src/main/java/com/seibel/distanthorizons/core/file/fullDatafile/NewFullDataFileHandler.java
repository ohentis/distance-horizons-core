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
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.NewFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.file.AbstractNewDataSourceHandler;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.sql.dto.NewFullDataSourceDTO;
import com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo;
import com.seibel.distanthorizons.core.sql.repo.NewFullDataSourceRepo;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.threading.ThreadPools;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class NewFullDataFileHandler 
		extends AbstractNewDataSourceHandler<NewFullDataSource, NewFullDataSourceDTO, IDhLevel> 
		implements IFullDataSourceProvider, IDebugRenderable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private static final int NUMBER_OF_PARENT_UPDATE_TASKS_PER_THREAD = 20;
	/** how many parent update tasks can be in the queue at once */
	private static final int MAX_PARENT_UPDATE_TASK_COUNT = NUMBER_OF_PARENT_UPDATE_TASKS_PER_THREAD * Config.Client.Advanced.MultiThreading.numberOfFileHandlerThreads.get();
	
	/** indicates how long the update queue thread should wait between queuing ticks */
	private static final int UPDATE_QUEUE_THREAD_DELAY_IN_MS = 1_000;
	
	
	// TODO add a debug view
	Set<DhSectionPos> parentApplicationPositionSet = ConcurrentHashMap.newKeySet();
	private final ThreadPoolExecutor updateQueueProcessor = ThreadUtil.makeSingleThreadPool("Update Queue Processor");
	private final AtomicBoolean updateQueueThreadRunningRef = new AtomicBoolean(false);
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public NewFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure) { this(level, saveStructure, null); }
	public NewFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure, @Nullable File saveDirOverride) 
	{
		super(level, saveStructure, saveDirOverride);
		
		DebugRenderer.register(this, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue);
	}
	
	
	
	//====================//
	// Abstract overrides //
	//====================//
	
	@Override
	protected AbstractDhRepo<DhSectionPos, NewFullDataSourceDTO> createRepo()
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
		// TODO maybe just set children update flags to true?
		return NewFullDataSource.createEmpty(pos);
	}
	
	@Override
	protected NewFullDataSource makeEmptyDataSource(DhSectionPos pos) { return NewFullDataSource.createEmpty(pos); }
	
	@Deprecated
	@Override
	public int getUnsavedDataSourceCount() { return 0; }
	
	
	
	//================//
	// parent updates //
	//================//
	
	@Override
	public void queuePositionForGenerationOrRetrievalIfNecessary(DhSectionPos pos)
	{
		// Do nothing.
		// This file handler doesn't have the ability to generate or retrieve data sources
		// that aren't already in the database
	}
	
	@Override
	protected void updateDataSourceAtPos(DhSectionPos pos, NewFullDataSource inputData, boolean lockOnPosition)
	{
		ReentrantLock updateLock = this.getUpdateLockForPos(pos);
		
		try
		{
			if (lockOnPosition)
			{
				updateLock.lock();
			}
			
			super.updateDataSourceAtPos(pos, inputData, false);
			this.tryQueueParentUpdates();
			
			this.parentApplicationPositionSet.remove(inputData.getSectionPos());
			if (pos.getDetailLevel() != inputData.getSectionPos().getDetailLevel())
			{
				// mark that the update has completed
				((NewFullDataSourceRepo) this.repo).setApplyToParent(inputData.getSectionPos(), false); // TODO remove casting
			}
		}
		catch (Exception e)
		{
			LOGGER.error("Error updating pos ["+pos+"], error: "+e.getMessage(), e);
		}
		finally
		{
			if (lockOnPosition)
			{
				updateLock.unlock();
			}
		}
	}
	/** Queues some of the parent updates listed in the database. */
	private void tryQueueParentUpdates()
	{
		// the update thread is already running,
		// we don't need multiple running
		if (this.updateQueueThreadRunningRef.getAndSet(true))
		{
			return;
		}
		
		
		this.updateQueueProcessor.execute(() ->
		{
			try
			{
				ArrayList<DhSectionPos> updatePosList = null;
				
				while ( // continue queuing update positions as long as there are positions to queue
						(updatePosList == null || updatePosList.size() != 0)
								// only add more items to the queue if half or more of the previous tasks have been completed
								&& this.parentApplicationPositionSet.size() < (MAX_PARENT_UPDATE_TASK_COUNT / 2))
				{
					// prevent hitting the database more often than is necessary
					Thread.sleep(UPDATE_QUEUE_THREAD_DELAY_IN_MS);
					
					
					// get the positions that need to be applied to their parents
					updatePosList = ((NewFullDataSourceRepo) this.repo).getPositionsToUpdate(MAX_PARENT_UPDATE_TASK_COUNT);
					if (updatePosList.size() != 0)
					{
						// stop if the file handler has been shut down
						ThreadPoolExecutor executor = ThreadPools.getFileHandlerExecutor();
						if (executor == null || executor.isTerminated())
						{
							this.updateQueueThreadRunningRef.set(false);
							return;
						}
						
						
						// queue each update
						int queueCount = 0;
						for (DhSectionPos pos : updatePosList)
						{
							if (this.parentApplicationPositionSet.add(pos))
							{
								queueCount++;
								executor.execute(() ->
								{
									NewFullDataSource inputData = this.get(pos);
									// update the parent position with this new data
									this.updateDataSourceAtPos(pos.getParentPos(), inputData, true);
									
									// TODO add comparable interface to make this low priority
								});
							}
						}
						
						// can be used for debugging
						if (queueCount != 0)
						{
							LOGGER.trace("Queued [" + queueCount + "] out of ["+updatePosList.size()+"] parent updates.");
						}
					}
				}
			}
			catch (Exception e)
			{
				LOGGER.error("Unexpected error in the parent update queue thread. Error: " + e.getMessage(), e);
			}
			finally
			{
				this.updateQueueThreadRunningRef.set(false);
			}
		});
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override
	public void debugRender(DebugRenderer renderer)
	{
		if (Config.Client.Advanced.Debugging.DebugWireframe.showFullDataFileStatus.get())
		{
			this.parentApplicationPositionSet
					.forEach((pos) -> { renderer.renderBox(new DebugRenderer.Box(pos, -32f, 128f, 0.15f, Color.cyan)); });
		}
	}
	
	
}
