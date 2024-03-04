package com.seibel.distanthorizons.core.file;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.NewFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo;
import com.seibel.distanthorizons.core.sql.dto.IBaseDTO;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.TimerUtil;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.ReentrantLock;

public abstract class AbstractNewDataSourceHandler
		<TDataSource extends IDataSource<TDhLevel>, 
				TDTO extends IBaseDTO<DhSectionPos>, 
				TDhLevel extends IDhLevel> 
		implements ISourceProvider<TDataSource, TDhLevel>
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	private static final Timer DELAYED_SAVE_TIMER = TimerUtil.CreateTimer("DataSourceSaveTimer");
	/** How long a data source must remain un-modified before being written to disk. */
	private static final int SAVE_DELAY_IN_MS = 4_000;
	
	/**
	 * The highest numerical detail level possible. 
	 * Used when determining which positions to update. 
	 * 
	 * @see AbstractNewDataSourceHandler#MIN_SECTION_DETAIL_LEVEL
	 */
	public static final byte TOP_SECTION_DETAIL_LEVEL = DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL + LodUtil.REGION_DETAIL_LEVEL; // TODO add "section" to detail level
	/** 
	 * The lowest numerical detail level possible. 
	 *
	 * @see AbstractNewDataSourceHandler#TOP_SECTION_DETAIL_LEVEL
	 * */
	public static final byte MIN_SECTION_DETAIL_LEVEL = DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
	
	
	protected final ReentrantLock[] updateLockArray;
	protected final ReentrantLock closeLock = new ReentrantLock();
	protected volatile boolean isShutdown = false;
	
	protected final TDhLevel level;
	protected final File saveDir;
	
	public final AbstractDhRepo<DhSectionPos, TDTO> repo;
	
	public final ArrayList<IDataSourceUpdateFunc<TDataSource>> dateSourceUpdateListeners = new ArrayList<>();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public AbstractNewDataSourceHandler(TDhLevel level, AbstractSaveStructure saveStructure) { this(level, saveStructure, null); }
	public AbstractNewDataSourceHandler(TDhLevel level, AbstractSaveStructure saveStructure, @Nullable File saveDirOverride)
	{
		this.level = level;
		this.saveDir = (saveDirOverride == null) ? saveStructure.getFullDataFolder(level.getLevelWrapper()) : saveDirOverride;
		if (!this.saveDir.exists() && !this.saveDir.mkdirs())
		{
			LOGGER.warn("Unable to create full data folder, file saving may fail.");
		}
		
		// the lock array's length is 4x the number of CPU cores so the number of collisions
		// should be relatively low without having too many extra locks
		int lockCount = Runtime.getRuntime().availableProcessors() * 4;
		this.updateLockArray = new ReentrantLock[lockCount];
		for (int i = 0; i < lockCount; i++)
		{
			this.updateLockArray[i] = new ReentrantLock();
		}
		
		this.repo = this.createRepo();
	}
	
	
	
	
	//==================//
	// abstract methods //
	//==================//
	
	/** When this is called the parent folders should be created */
	protected abstract AbstractDhRepo<DhSectionPos, TDTO> createRepo();
	
	protected abstract TDataSource createDataSourceFromDto(TDTO dto) throws InterruptedException, IOException;
	protected abstract TDTO createDtoFromDataSource(TDataSource dataSource);
	
	/** Creates a new data source using any DTOs already present in the database. */
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
	@Override
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
	 * @see AbstractNewDataSourceHandler#getAsync(DhSectionPos)
	 */
	public TDataSource get(DhSectionPos pos)
	{
		TDataSource dataSource = null;
		try
		{
			TDTO dto = this.repo.getByKey(pos);
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
	
	@Override
	public CompletableFuture<Void> updateDataSourceAsync(NewFullDataSource inputDataSource)
	{
		ThreadPoolExecutor executor = ThreadPoolUtil.getUpdatePropagatorExecutor();
		if (executor == null || executor.isTerminated())
		{
			return CompletableFuture.completedFuture(null);
		}
		
		
		try
		{
			// run file handling on a separate thread
			return CompletableFuture.runAsync(() ->
			{
				this.updateDataSourceAtPos(inputDataSource.getSectionPos(), inputDataSource, true);
			}, executor);
		}
		catch (RejectedExecutionException ignore)
		{
			// can happen if the executor was shutdown while this task was queued
			return CompletableFuture.completedFuture(null);
		}
	}
	/**
	 * @param pos the position to update
	 * @param lockOnPosition Can be disabled by inheriting children to allow for their own locking logic.
	 *                       This is important if the child has its own position specific logic that shouldn't be done concurrently.
	 */
	protected void updateDataSourceAtPos(DhSectionPos pos, NewFullDataSource inputData, boolean lockOnPosition)
	{
		// a lock is necessary to prevent two threads from writing to the same position at once,
		// if that happens only the second update will apply and the LOD will end up with hole(s)
		ReentrantLock updateLock = this.getUpdateLockForPos(pos);
		
		try
		{
			if (lockOnPosition)
			{
				updateLock.lock();
			}
			
			
			// get or create the data source
			TDataSource dataSource = this.get(pos);
			boolean dataModified = dataSource.update(inputData, this.level);
			
			if (dataModified)
			{
				ThreadPoolExecutor executor = ThreadPoolUtil.getFileHandlerExecutor();
				if (executor == null || executor.isTerminated())
				{
					return;
				}
				
				executor.execute(() -> 
				{
					// save the updated data to the database
					TDTO dto = this.createDtoFromDataSource(dataSource);
					this.repo.save(dto);
					
					
					for (IDataSourceUpdateFunc<TDataSource> listener : this.dateSourceUpdateListeners)
					{
						if (listener != null)
						{
							listener.OnDataSourceUpdated(dataSource);
						}
					}
				});
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
	
	
	
	//================//
	// helper methods //
	//================//
	
	/** Based on the stack overflow post: https://stackoverflow.com/a/45909920 */
	protected ReentrantLock getUpdateLockForPos(DhSectionPos pos) { return this.updateLockArray[Math.abs(pos.hashCode()) % this.updateLockArray.length]; }
	
	
	
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
			
			LOGGER.info("Closing [" + this.getClass().getSimpleName() + "] for level: [" + this.level + "].");
			
			this.repo.close();
		}
		catch (InterruptedException ignore) { }
		finally
		{
			this.closeLock.unlock();
		}
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	@FunctionalInterface
	public interface IDataSourceUpdateFunc<TDataSource>
	{
		void OnDataSourceUpdated(TDataSource updatedFullDataSource);
	}
	
}
