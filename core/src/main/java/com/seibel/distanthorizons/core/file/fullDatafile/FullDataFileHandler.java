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
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.loader.AbstractFullDataSourceLoader;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.HighDetailIncompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.LowDetailIncompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IIncompleteFullDataSource;
import com.seibel.distanthorizons.core.file.metaData.AbstractMetaDataContainerFile;
import com.seibel.distanthorizons.core.file.metaData.BaseMetaData;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.sql.FullDataRepo;
import com.seibel.distanthorizons.core.sql.MetaDataDto;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import com.seibel.distanthorizons.core.util.threading.ThreadPools;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.Adler32;
import java.util.zip.CheckedOutputStream;

public class FullDataFileHandler implements IFullDataSourceProvider
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	private static final Timer DELAYED_SAVE_TIMER = new Timer();
	/** How long a data source must remain un-modified before being written to disk. */
	private static final int SAVE_DELAY_IN_MS = 4_000;
	
	protected final ConcurrentHashMap<DhSectionPos, IFullDataSource> unsavedDataSourceBySectionPos = new ConcurrentHashMap<>();
	protected final ConcurrentHashMap<DhSectionPos, TimerTask> saveTimerTasksBySectionPos = new ConcurrentHashMap<>();
	protected final ReentrantLock[] updateLockArray;
	
	protected final IDhLevel level;
	protected final File saveDir;
	
	/** 
	 * The highest numerical detail level known about. 
	 * Used when determining which positions to update. 
	 */
	protected final AtomicInteger topSectionDetailLevelRef;
	protected final int minDetailLevel = CompleteFullDataSource.SECTION_SIZE_OFFSET;
	
	public final FullDataRepo fullDataRepo;
	@Override
	public FullDataRepo getRepo() { return this.fullDataRepo; }
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public FullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure) { this(level, saveStructure, null); }
	public FullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure, @Nullable File saveDirOverride)
	{
		this.level = level;
		this.saveDir = (saveDirOverride == null) ? saveStructure.getFullDataFolder(level.getLevelWrapper()) : saveDirOverride;
		if (!this.saveDir.exists() && !this.saveDir.mkdirs())
		{
			LOGGER.warn("Unable to create full data folder, file saving may fail.");
		}
		
		// the lock array's length is double the number of CPU cores so the number of collisions
		// should be relatively low without having too many extra locks
		this.updateLockArray = new ReentrantLock[Runtime.getRuntime().availableProcessors() * 2];
		for (int i = 0; i < this.updateLockArray.length; i++)
		{
			this.updateLockArray[i] = new ReentrantLock();
		}
		
		
		try
		{
			this.fullDataRepo = new FullDataRepo("jdbc:sqlite", this.saveDir.getPath() + "/" + AbstractSaveStructure.DATABASE_NAME);
		}
		catch (SQLException e)
		{
			// should only happen if there is an issue with the database (it's locked or can't be created if missing) 
			// or the database update failed
			throw new RuntimeException(e);
		}
		
		// determine the top detail level currently in the database
		int maxSectionDetailLevel = this.fullDataRepo.getMaxSectionDetailLevel();
		this.topSectionDetailLevelRef = new AtomicInteger(maxSectionDetailLevel);
	}
	
	
	
	//==============//
	// data reading //
	//==============//
	
	/**
	 * Returns the {@link IFullDataSource} for the given section position. <Br>
	 * The returned data source may be null. <Br> <Br>
	 *
	 * For now, if result is null, it prob means error has occurred when loading or creating the file object. <Br> <Br>
	 *
	 * This call is concurrent. I.e. it supports being called by multiple threads at the same time.
	 */
	@Override
	public CompletableFuture<IFullDataSource> getAsync(DhSectionPos pos)
	{
		ThreadPoolExecutor executor = ThreadPools.getFileHandlerExecutor();	
		if (executor == null || executor.isTerminated())
		{
			return CompletableFuture.completedFuture(null);
		}
		
		return CompletableFuture.supplyAsync(() -> this.get(pos), executor);
	}
	/** 
	 * Should be used in internal methods where we are already running on a file handler thread. 
	 * @see FullDataFileHandler#getAsync(DhSectionPos)  
	 */
	protected IFullDataSource get(DhSectionPos pos)
	{
		// used the unsaved data source if present
		if (this.unsavedDataSourceBySectionPos.containsKey(pos))
		{
			return this.unsavedDataSourceBySectionPos.get(pos);
		}
		// an unsaved data source isn't present
		// check the database
		
		
		// increase the top detail level if necessary
		this.topSectionDetailLevelRef.updateAndGet(oldDetailLevel -> Math.max(oldDetailLevel, pos.getDetailLevel()));
		
		
		IFullDataSource dataSource = null;
		try
		{
			MetaDataDto dto = this.fullDataRepo.getByPrimaryKey(pos.serialize());
			if (dto != null)
			{
				// load from file
				AbstractFullDataSourceLoader loader = AbstractFullDataSourceLoader.getLoader(dto.baseMetaData.dataType, dto.baseMetaData.binaryDataFormatVersion);
				dataSource = loader.loadDataSource(dto, this.level);
			}
			else
			{
				// attempt to create from any existing files
				dataSource = this.createNewDataSourceFromExistingDtos(pos);
			}
		}
		catch (InterruptedException ignore) { }
		catch (IOException e)
		{
			LOGGER.warn("File read Error for pos ["+pos+"], error: "+e.getMessage(), e);
		}
		
		return dataSource;
	}
	/** Creates a new data source using any DTOs already present in the database. */
	protected IFullDataSource createNewDataSourceFromExistingDtos(DhSectionPos pos)
	{
		IIncompleteFullDataSource newFullDataSource = this.makeEmptyDataSource(pos);
		
		
		boolean showFullDataFileSampling = Config.Client.Advanced.Debugging.DebugWireframe.showFullDataFileStatus.get();
		if (showFullDataFileSampling)
		{
			DebugRenderer.makeParticle(new DebugRenderer.BoxParticle(
					new DebugRenderer.Box(newFullDataSource.getSectionPos(), 64f, 72f, 0.03f, Color.MAGENTA),
					0.2, 32f));
		}
		
		
		// TODO replace with a SQL query, it should be much faster
		ArrayList<DhSectionPos> samplePosList = new ArrayList<>();
		ArrayList<DhSectionPos> possibleChildList = new ArrayList<>();
		pos.forEachChild((childPos) ->
		{
			if (childPos.getDetailLevel() > this.minDetailLevel)
			{
				possibleChildList.add(childPos);
			}
		});
		while (possibleChildList.size() != 0)
		{
			DhSectionPos possiblePos = possibleChildList.remove(possibleChildList.size()-1);
			if (this.fullDataRepo.existsWithPrimaryKey(possiblePos.serialize()))
			{
				samplePosList.add(possiblePos);
			}
			else
			{
				possiblePos.forEachChild((childPos) ->
				{
					if (childPos.getDetailLevel() > this.minDetailLevel)
					{
						possibleChildList.add(childPos);
					}
				});
			}
		}
		
		
		// read in the existing data
		for (int i = 0; i < samplePosList.size(); i++)
		{
			DhSectionPos samplePos = samplePosList.get(i);
			IFullDataSource sampleDataSource = this.get(samplePos);
			if (sampleDataSource == null)
			{
				// no file was found, this is unexpected, but can be ignored
				continue;
			}
			
			if (showFullDataFileSampling)
			{
				DebugRenderer.makeParticle(new DebugRenderer.BoxParticle(
						new DebugRenderer.Box(newFullDataSource.getSectionPos(), 64f, 72f, 0.03f, Color.MAGENTA.darker()),
						0.2, 32f));
			}
			
			try
			{
				newFullDataSource.sampleFrom(sampleDataSource);
			}
			catch (Exception e)
			{
				LOGGER.warn("Unable to sample "+sampleDataSource.getSectionPos()+" into "+newFullDataSource.getSectionPos(), e);
			}
		}
		
		
		// promotion may happen if all children are fully populated
		return newFullDataSource.tryPromotingToCompleteDataSource();
	}
	
	
	
	//===============//
	// data updating //
	//===============//
	
	@Override
	public void updateDataSourcesWithChunkData(ChunkSizedFullDataAccessor chunkDataView)
	{
		DhSectionPos chunkSectionPos = chunkDataView.getSectionPos().convertNewToDetailLevel(CompleteFullDataSource.SECTION_SIZE_OFFSET);
		this.recursivelyUpdateDataSourcesAsync(chunkSectionPos, chunkDataView);
	}
	/** Updates every data source from this position up to {@link FullDataFileHandler#topSectionDetailLevelRef} */
	private void recursivelyUpdateDataSourcesAsync(DhSectionPos pos, ChunkSizedFullDataAccessor chunkDataView)
	{
		ThreadPoolExecutor executor = ThreadPools.getFileHandlerExecutor();
		if (executor == null || executor.isTerminated())
		{
			return;
		}
		
		// update up until we reach the highest available data source
		if (pos.getDetailLevel() > this.topSectionDetailLevelRef.get())
		{
			return;
		}
		
		
		executor.execute(() ->
		{
			DhSectionPos chunkSectionPos = chunkDataView.getSectionPos();
			LodUtil.assertTrue(chunkSectionPos.overlapsExactly(pos), "Update failed, chunk [" + chunkSectionPos + "] does not overlap section [" + pos + "].");
			
			// update this pos
			this.updateDataSourceAtPos(pos, chunkDataView);
			
			// recursively update the parent pos
			DhSectionPos parentPos = pos.getParentPos();
			this.recursivelyUpdateDataSourcesAsync(parentPos, chunkDataView);
			
		});
	}
	private void updateDataSourceAtPos(DhSectionPos pos, ChunkSizedFullDataAccessor chunkDataView)
	{
		// a lock is necessary to prevent two threads from writing to the same position at once,
		// if that happens only the second update will apply and the LOD will end up with hole(s)
		ReentrantLock updateLock = this.getUpdateLockForPos(pos);
		
		try
		{
			updateLock.lock();
			
			// get or create the data source
			IFullDataSource fullDataSource = this.get(pos);
			if (fullDataSource == null)
			{
				fullDataSource = this.makeEmptyDataSource(pos);
			}
			fullDataSource.update(chunkDataView);
			
			// try promoting the datasource
			if (fullDataSource instanceof IIncompleteFullDataSource)
			{
				IIncompleteFullDataSource incompleteFullDataSource = (IIncompleteFullDataSource) fullDataSource;
				fullDataSource = incompleteFullDataSource.tryPromotingToCompleteDataSource();
			}
			
			this.queueDelayedSave(fullDataSource);
		}
		catch (Exception e)
		{
			LOGGER.error("Error updating pos ["+pos+"], error: "+e.getMessage(), e);
		}
		finally
		{
			updateLock.unlock();
		}
	}
	/** 
	 * Queues the given data source to save after {@link FullDataFileHandler#SAVE_DELAY_IN_MS}
	 * milliseconds have passed without any additional modifications. <br> <br>
	 * 
	 * This prevents repeatedly reading/writing the same data source to/from disk if said 
	 * source is currently being updated via world gen or chunk modifications.
	 * This drastically reduces disk usage and improves performance.
	 */
	private void queueDelayedSave(IFullDataSource fullDataSource)
	{
		DhSectionPos pos = fullDataSource.getSectionPos();
		
		// put the data source in memory until it can be flushed to disk
		this.unsavedDataSourceBySectionPos.put(pos, fullDataSource);
		
		TimerTask task = new TimerTask()
		{
			@Override
			public void run()
			{
				try
				{
					final IFullDataSource finalFullDataSource = FullDataFileHandler.this.unsavedDataSourceBySectionPos.remove(pos);
					
					// this can rarely happen due to imperfect concurrency handling,
					// if the data source is null that just means it has already been saved so nothing needs to be done 
					if (finalFullDataSource != null)
					{
						FullDataFileHandler.this.writeDataSourceToFile(finalFullDataSource, (bufferedOutputStream) ->
						{
							try
							{
								finalFullDataSource.writeToStream(bufferedOutputStream, FullDataFileHandler.this.level);
							}
							catch (Exception e)
							{
								// if this try catch isn't included an empty exception will be thrown instead, which makes debugging extremely painful
								LOGGER.error("Error writing data stream for pos: [" + finalFullDataSource.getSectionPos() + "], error: " + e.getMessage(), e);
							}
						});
					}
				}
				catch (ClosedByInterruptException e) // thrown by buffers that are interrupted
				{
					// expected if the file handler is shut down, the exception can be ignored
					//LOGGER.warn("FullData file writing interrupted.", e);
				}
				catch (IOException e)
				{
					LOGGER.error("Failed to save updated data for section " + pos, e);
				}
			}
		};
		DELAYED_SAVE_TIMER.schedule(task, SAVE_DELAY_IN_MS);
		
		// 
		TimerTask oldTask = this.saveTimerTasksBySectionPos.put(pos, task);
		if (oldTask != null)
		{
			oldTask.cancel();
		}
	}
	private void writeDataSourceToFile(IFullDataSource fullDataSource, AbstractMetaDataContainerFile.IMetaDataWriterFunc<DhDataOutputStream> dataWriterFunc) throws IOException
	{
		LodUtil.assertTrue(fullDataSource != null);
		
		
		boolean showFullDataFileStatus = Config.Client.Advanced.Debugging.DebugWireframe.showFullDataFileStatus.get();
		if (showFullDataFileStatus)
		{
			DebugRenderer.makeParticle(new DebugRenderer.BoxParticle(
					new DebugRenderer.Box(fullDataSource.getSectionPos(), 64f, 70f, 0.02f, Color.YELLOW),
					0.2, 16f));
		}
		
		try
		{
			// write the outputs to a stream to prep for writing to the database
			ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
			
			// the order of these streams is important, otherwise the checksum won't be calculated
			CheckedOutputStream checkedOut = new CheckedOutputStream(byteArrayOutputStream, new Adler32());
			// normally a DhStream should be the topmost stream to prevent closing the stream accidentally, 
			// but since this stream will be closed immediately after writing anyway, it won't be an issue
			DhDataOutputStream compressedOut = new DhDataOutputStream(checkedOut);
			
			dataWriterFunc.writeBinaryDataToStream(compressedOut);
			
			compressedOut.flush();
			int checksum = (int) checkedOut.getChecksum().getValue();
			byteArrayOutputStream.close();
			
			
			// save the DTO
			BaseMetaData baseMetaData = new BaseMetaData(fullDataSource.getSectionPos(), checksum,
					fullDataSource.getDataDetailLevel(), fullDataSource.getWorldGenStep(), fullDataSource.getDataTypeName(),
					fullDataSource.getDataFormatVersion());
			MetaDataDto newDto = new MetaDataDto(baseMetaData, byteArrayOutputStream.toByteArray());
			this.fullDataRepo.save(newDto);
		}
		catch (ClosedChannelException e) // includes ClosedByInterruptException
		{
			// expected if the file handler is shut down, the exception can be ignored
			//LOGGER.warn(AbstractMetaDataContainerFile.class.getSimpleName()+" file writing interrupted. Error: "+e.getMessage());
		}
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	/** Based on the stack overflow post: https://stackoverflow.com/a/45909920 */
	protected ReentrantLock getUpdateLockForPos(DhSectionPos pos) { return this.updateLockArray[Math.abs(pos.hashCode()) % this.updateLockArray.length]; }
	
	protected IIncompleteFullDataSource makeEmptyDataSource(DhSectionPos pos)
	{
		return pos.getDetailLevel() <= HighDetailIncompleteFullDataSource.MAX_SECTION_DETAIL ?
				HighDetailIncompleteFullDataSource.createEmpty(pos) :
				LowDetailIncompleteFullDataSource.createEmpty(pos);
	}
	
	
	
	//=========//
	// cleanup //
	//=========//
	
	@Override
	public void close() 
	{
		LOGGER.info("Closing file handler for level: ["+this.level+"], saving ["+this.saveTimerTasksBySectionPos.size()+"] positions.");
		
		Enumeration<DhSectionPos> list = this.saveTimerTasksBySectionPos.keys();
		while (list.hasMoreElements())
		{
			DhSectionPos pos = list.nextElement();
			TimerTask saveTask = this.saveTimerTasksBySectionPos.remove(pos);
			if (saveTask != null)
			{
				saveTask.run();
				saveTask.cancel();
			}
		}
		
		LOGGER.info("File handler saving complete, closing repo.");
		this.fullDataRepo.close(); 
	}
	
}
