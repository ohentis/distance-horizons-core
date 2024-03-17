package com.seibel.distanthorizons.core.file;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.LegacyDataSourceDTO;
import com.seibel.distanthorizons.core.sql.repo.AbstractLegacyDataSourceRepo;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.TimerUtil;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.Enumeration;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.Adler32;
import java.util.zip.CheckedOutputStream;

@Deprecated
public abstract class AbstractLegacyDataSourceHandler<TDataSource 
		extends IDataSource<TDhLevel>, TDhLevel extends IDhLevel>
		implements AutoCloseable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	private static final Timer DELAYED_SAVE_TIMER = TimerUtil.CreateTimer("DataSourceSaveTimer");
	/** How long a data source must remain un-modified before being written to disk. */
	private static final int SAVE_DELAY_IN_MS = 4_000;
	
	/**
	 * The highest numerical detail level known about. 
	 * Used when determining which positions to update. 
	 */
	protected final AtomicInteger topSectionDetailLevelRef;
	protected final int minDetailLevel = DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
	
	protected final ConcurrentHashMap<DhSectionPos, TDataSource> unsavedDataSourceBySectionPos = new ConcurrentHashMap<>();
	protected final ConcurrentHashMap<DhSectionPos, TimerTask> saveTimerTasksBySectionPos = new ConcurrentHashMap<>();
	
	protected final ReentrantLock[] updateLockArray;
	protected final ReentrantLock[] queueSaveLockArray;
	protected final ReentrantLock closeLock = new ReentrantLock();
	protected volatile boolean isShutdown = false;
	
	protected final TDhLevel level;
	protected final File saveDir;
	
	public final AbstractLegacyDataSourceRepo repo;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public AbstractLegacyDataSourceHandler(TDhLevel level, AbstractSaveStructure saveStructure) { this(level, saveStructure, null); }
	public AbstractLegacyDataSourceHandler(TDhLevel level, AbstractSaveStructure saveStructure, @Nullable File saveDirOverride)
	{
		this.level = level;
		this.saveDir = (saveDirOverride == null) ? saveStructure.getFullDataFolder(level.getLevelWrapper()) : saveDirOverride;
		if (!this.saveDir.exists() && !this.saveDir.mkdirs())
		{
			LOGGER.warn("Unable to create full data folder, file saving may fail.");
		}
		
		// the lock arrays' length is double the number of CPU cores so the number of collisions
		// should be relatively low without having too many extra locks
		int lockCount = Runtime.getRuntime().availableProcessors() * 2;
		this.updateLockArray = new ReentrantLock[lockCount];
		this.queueSaveLockArray = new ReentrantLock[lockCount];
		for (int i = 0; i < lockCount; i++)
		{
			this.updateLockArray[i] = new ReentrantLock();
			this.queueSaveLockArray[i] = new ReentrantLock();
		}
		
		this.repo = this.createRepo();
		
		// determine the top detail level currently in the database
		int maxSectionDetailLevel = this.repo.getMaxSectionDetailLevel();
		this.topSectionDetailLevelRef = new AtomicInteger(maxSectionDetailLevel);
	}
	
	
	
	
	//==================//
	// abstract methods //
	//==================//
	
	/** When this is called the parent folders should be created */
	protected abstract AbstractLegacyDataSourceRepo createRepo();
	
	protected abstract TDataSource createDataSourceFromDto(LegacyDataSourceDTO dto) throws InterruptedException, IOException;
	/** 
	 * Creates a new data source using any DTOs already present in the database. 
	 * Can return null if there was an issue, but in general should return at least an empty data source.
	 */
	@Nullable
	protected abstract TDataSource createNewDataSourceFromExistingDtos(DhSectionPos pos);
	
	protected abstract TDataSource makeEmptyDataSource(DhSectionPos pos);
	
	
	
	//==============//
	// data reading //
	//==============//
	
	/**
	 * Returns the {@link TDataSource} for the given section position. <Br>
	 * The returned data source may be null if there was a problem. <Br> <Br>
	 *
	 * This call is concurrent. I.e. it supports being called by multiple threads at the same time.
	 */
	public CompletableFuture<TDataSource> getAsync(DhSectionPos pos)
	{
		ThreadPoolExecutor executor = ThreadPoolUtil.getFileHandlerExecutor();
		if (executor == null || executor.isTerminated())
		{
			return CompletableFuture.completedFuture(null);
		}
		
		return CompletableFuture.supplyAsync(() -> this.get(pos), executor);
	}
	/**
	 * Should only be used in internal file handler methods where we are already running on a file handler thread.
	 * Can return null if there was a problem.
	 * @see AbstractLegacyDataSourceHandler#getAsync(DhSectionPos)
	 */
	@Nullable
	public TDataSource get(DhSectionPos pos)
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
		
		
		TDataSource dataSource = null;
		try
		{
			LegacyDataSourceDTO dto = this.repo.getByKey(pos);
			if (dto != null)
			{
				// load from file
				dataSource = this.createDataSourceFromDto(dto);
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
	
	
	
	//===============//
	// data updating //
	//===============//
	
	public CompletableFuture<Void> updateDataSourceAsync(FullDataSourceV2 inputDataSource)
	{
		ThreadPoolExecutor executor = ThreadPoolUtil.getFileHandlerExecutor();
		if (executor == null || executor.isTerminated())
		{
			return CompletableFuture.completedFuture(null);
		}
		
		
		try
		{
			// run file handling on a separate thread
			return CompletableFuture.runAsync(() ->
			{
				DhSectionPos bottomPos = inputDataSource.getSectionPos().convertNewToDetailLevel(DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
				
				bottomPos.forEachPosUpToDetailLevel(
						this.topSectionDetailLevelRef.byteValue(), 
						(pos) -> this.updateDataSourceAtPos(pos, inputDataSource) );
				
			}, executor);
		}
		catch (RejectedExecutionException ignore)
		{
			// can happen if the executor was shutdown while this task was queued
			return CompletableFuture.completedFuture(null);
		}
	}
	protected void updateDataSourceAtPos(DhSectionPos pos, FullDataSourceV2 newDataSource)
	{
		// a lock is necessary to prevent two threads from writing to the same position at once,
		// if that happens only the second update will apply and the LOD will end up with hole(s)
		ReentrantLock updateLock = this.getUpdateLockForPos(pos);
		
		try
		{
			updateLock.lock();
			
			// get or create the data source
			TDataSource dataSource = this.get(pos);
			if (dataSource == null)
			{
				dataSource = this.makeEmptyDataSource(pos);
			}
			dataSource.update(newDataSource, this.level);
			
			this.queueDelayedSave(dataSource);
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
	 * Queues the given data source to save after {@link AbstractLegacyDataSourceHandler#SAVE_DELAY_IN_MS}
	 * milliseconds have passed without any additional modifications. <br> <br>
	 *
	 * This prevents repeatedly reading/writing the same data source to/from disk if said 
	 * source is currently being updated via world gen or chunk modifications.
	 * This drastically reduces disk usage and improves performance.
	 */
	protected void queueDelayedSave(TDataSource dataSource)
	{
		// a lock is necessary to prevent two threads from queuing a save at the same time,
		// which can cause the timer to queue canceled tasks
		DhSectionPos pos = dataSource.getSectionPos();
		ReentrantLock saveQueueLock = this.getSaveQueueLockForPos(pos);
		
		
		// done to prevent queueing saves while the current queue is being cleared
		if (this.isShutdown)
		{
			return;
		}
		
		
		try
		{
			saveQueueLock.lock();
			
			// put the data source in memory until it can be flushed to disk
			this.unsavedDataSourceBySectionPos.put(pos, dataSource);
			
			TimerTask task = new TimerTask()
			{
				@Override
				public void run()
				{
					
					// remove this task from the queue
					AbstractLegacyDataSourceHandler.this.saveTimerTasksBySectionPos.remove(pos);
					
					try
					{
						final TDataSource finalDataSource = AbstractLegacyDataSourceHandler.this.unsavedDataSourceBySectionPos.remove(pos);
						
						// this can rarely happen due to imperfect concurrency handling,
						// if the data source is null that just means it has already been saved so nothing needs to be done 
						if (finalDataSource != null)
						{
							AbstractLegacyDataSourceHandler.this.writeDataSourceToFile(finalDataSource);
						}
					}
					catch (Exception e) // this can throw errors (not exceptions) when installed in Iris' dev environment for some reason due to an issue with LZ4's compression library
					{
						LOGGER.error("Failed to save updated data for section ["+pos+"], error: ["+e.getMessage()+"]", e);
					}
				}
			};
			try
			{
				DELAYED_SAVE_TIMER.schedule(task, SAVE_DELAY_IN_MS);
			}
			catch (IllegalStateException ignore)
			{
				// James isn't sure why this is possible since this logic is inside a lock, 
				// maybe the timer is just async enough that there can be problems?
				LOGGER.warn("Attempted to queue an already canceled task. Pos: ["+pos+"], task already queued for pos: ["+this.saveTimerTasksBySectionPos.containsKey(pos)+"]");
			}
			
			
			// cancel the old save timer if present
			// (this is equivalent to restarting the timer)
			TimerTask oldTask = this.saveTimerTasksBySectionPos.put(pos, task);
			if (oldTask != null)
			{
				oldTask.cancel();
			}
		}
		finally
		{
			saveQueueLock.unlock();
		}
	}
	protected void writeDataSourceToFile(TDataSource dataSource) throws IOException
	{
		LodUtil.assertTrue(dataSource != null);
		
		try
		{
			// write the outputs to a stream to prep for writing to the database
			ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
			
			// the order of these streams is important, otherwise the checksum won't be calculated
			CheckedOutputStream checkedOut = new CheckedOutputStream(byteArrayOutputStream, new Adler32());
			// normally a DhStream should be the topmost stream to prevent closing the stream accidentally, 
			// but since this stream will be closed immediately after writing anyway, it won't be an issue
			DhDataOutputStream compressedOut = new DhDataOutputStream(checkedOut, EDhApiDataCompressionMode.LZ4);
			
			dataSource.writeToStream(compressedOut, AbstractLegacyDataSourceHandler.this.level);
			
			compressedOut.flush();
			int checksum = (int) checkedOut.getChecksum().getValue();
			byteArrayOutputStream.close();
			
			
			// save the DTO
			LegacyDataSourceDTO newDto = new LegacyDataSourceDTO(
					dataSource.getSectionPos(), checksum,
					dataSource.getDataDetailLevel(), EDhApiWorldGenerationStep.EMPTY, ColumnRenderSource.DATA_NAME,
					dataSource.getDataFormatVersion(), 
					byteArrayOutputStream.toByteArray());
			this.repo.save(newDto);
		}
		catch (ClosedChannelException e) // includes ClosedByInterruptException
		{
			// expected if the file handler is shut down, the exception can be ignored
		}
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	/** Based on the stack overflow post: https://stackoverflow.com/a/45909920 */
	protected ReentrantLock getUpdateLockForPos(DhSectionPos pos) { return this.updateLockArray[Math.abs(pos.hashCode()) % this.updateLockArray.length]; }
	protected ReentrantLock getSaveQueueLockForPos(DhSectionPos pos) { return this.queueSaveLockArray[Math.abs(pos.hashCode()) % this.queueSaveLockArray.length]; }
	
	
	
	//=========//
	// cleanup //
	//=========//
	
	@Override
	public void close()
	{
		try
		{
			this.closeLock.lock();
			this.isShutdown = true;
			
			// wait a moment so any queued saves can finish queuing, 
			// otherwise we might not see everything that needs saving and attempt to use a closed repo
			Thread.sleep(200);
			
			LOGGER.info("Closing [" + this.getClass().getSimpleName() + "] for level: [" + this.level + "], saving [" + this.saveTimerTasksBySectionPos.size() + "] positions.");
			
			
			Enumeration<DhSectionPos> list = this.saveTimerTasksBySectionPos.keys();
			while (list.hasMoreElements())
			{
				DhSectionPos pos = list.nextElement();
				TimerTask saveTask = this.saveTimerTasksBySectionPos.remove(pos);
				if (saveTask != null)
				{
					saveTask.run();
					// canceling the task doesn't need to be done since the it has internal logic to prevent running more than once
				}
			}
			
			LOGGER.info("[" + this.getClass().getSimpleName() + "] saving complete, closing repo.");
			this.repo.close();
		}
		catch (InterruptedException ignore) { }
		finally
		{
			this.closeLock.unlock();
		}
	}
	
}
