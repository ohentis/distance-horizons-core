package com.seibel.distanthorizons.core.file.fullDatafile;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalNotification;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.KeyedLockContainer;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Used to batch together multiple data source updates that all
 * affect the same position.
 */
public class DelayedFullDataSourceSaveCache
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	
	private final Cache<Long, FullDataSourceV2> dataSourceByPosition;
	
	/* don't let two threads load the same position at the same time */
	protected final KeyedLockContainer<Long> saveLockContainer = new KeyedLockContainer<>();
	
	private final ISaveDataSourceFunc onSaveTimeoutAsyncFunc;
	private final int saveDelayInMs;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public DelayedFullDataSourceSaveCache(@NotNull ISaveDataSourceFunc onSaveTimeoutAsyncFunc, int saveDelayInMs)
	{
		this.onSaveTimeoutAsyncFunc = onSaveTimeoutAsyncFunc;
		this.saveDelayInMs = saveDelayInMs;
		
		
		this.dataSourceByPosition =
			CacheBuilder.newBuilder()
				.expireAfterAccess(this.saveDelayInMs, TimeUnit.MILLISECONDS)
				.expireAfterWrite(this.saveDelayInMs, TimeUnit.MILLISECONDS)
				.removalListener(this::handleDataSourceRemoval)
				.<Long, FullDataSourceV2>build();
		
	}
	
	
	
	//==============//
	// update queue //
	//==============//
	
	/**
	 * Writing into memory is done synchronously so inputDataSource can 
	 * be closed after this method finishes.
	 */
	public void writeDataSourceToMemoryAndQueueSave(FullDataSourceV2 inputDataSource)
	{
		long inputPos = inputDataSource.getPos();
		
		ReentrantLock lock = this.saveLockContainer.getLockForPos(inputPos);
		try
		{
			lock.lock();
			
			FullDataSourceV2 memoryDataSource = this.dataSourceByPosition.getIfPresent(inputPos);
			if (memoryDataSource == null)
			{
				memoryDataSource = FullDataSourceV2.createEmpty(inputPos);
			}
			memoryDataSource.update(inputDataSource);
			this.dataSourceByPosition.put(inputPos, memoryDataSource);
		}
		finally
		{
			lock.unlock();
		}
	}
	
	public int getUnsavedCount() { return (int)this.dataSourceByPosition.size(); }
	
	
	public void handleDataSourceRemoval(RemovalNotification<Long, FullDataSourceV2> removalNotification)
	{
		RemovalCause cause = removalNotification.getCause();
		if (cause == RemovalCause.EXPIRED
			|| cause == RemovalCause.COLLECTED
			|| cause == RemovalCause.SIZE)
		{
			// close the data source after it has expired from the cache
			FullDataSourceV2 dataSource = removalNotification.getValue();
			if (dataSource != null)
			{
				this.onSaveTimeoutAsyncFunc.saveAsync(dataSource)
					.handle((voidObj, throwable) ->
					{
						try
						{
							dataSource.close();
						}
						catch (Exception e)
						{
							LOGGER.error("Unable to close datasource ["+ DhSectionPos.toString(dataSource.getPos()) +"], removal cause: ["+cause+"], error: ["+e.getMessage()+"].", e);
						}
						
						return null;
					});
			}
			else
			{
				LOGGER.error("Unable to close null cached data source.");
			}
		}
	}
	
	
	
	
	//================//
	// helper classes //
	//================//
	
	@FunctionalInterface
	public interface ISaveDataSourceFunc
	{
		/** called after the timeout expires */
		CompletableFuture<Void> saveAsync(FullDataSourceV2 inputDataSource);
	}
	
}
