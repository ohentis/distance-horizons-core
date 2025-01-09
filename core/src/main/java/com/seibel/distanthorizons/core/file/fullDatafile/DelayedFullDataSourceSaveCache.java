package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.TimerUtil;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Used to batch together multiple data source updates that all
 * affect the same position.
 * 
 * @deprecated due to causing data source leaks, however we may still want to re-visit this 
 * if saving directly is too slow for certain operations (specifically modifying nearby chunks).
 */
@Deprecated
public class DelayedFullDataSourceSaveCache
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private static final Timer DELAY_UPDATE_TIMER = TimerUtil.CreateTimer("Delayed Full Datasource Save Timer");
	
	
	public final ConcurrentHashMap<Long, FullDataSourceV2> dataSourceByPosition = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, TimerTask> saveTimerTasksBySectionPos = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, CompletableFuture<Void>> futureBySectionPos = new ConcurrentHashMap<>();
	
	protected final ReentrantLock[] saveLockArray;
	/** Based on the stack overflow post: https://stackoverflow.com/a/45909920 */
	protected ReentrantLock getSaveLockForPos(long pos) { return this.saveLockArray[Math.abs(Long.hashCode(pos)) % this.saveLockArray.length]; }
	
	
	private final ISaveDataSourceFunc onSaveTimeoutAsyncFunc;
	private final int saveDelayInMs;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public DelayedFullDataSourceSaveCache(@NotNull ISaveDataSourceFunc onSaveTimeoutAsyncFunc, int saveDelayInMs)
	{
		this.onSaveTimeoutAsyncFunc = onSaveTimeoutAsyncFunc;
		this.saveDelayInMs = saveDelayInMs;
		
		
		// the lock array's length is 2x the number of CPU cores so the number of collisions
		// should be relatively low without having too many extra locks
		int lockCount = Runtime.getRuntime().availableProcessors() * 2;
		this.saveLockArray = new ReentrantLock[lockCount];
		for (int i = 0; i < lockCount; i++)
		{
			this.saveLockArray[i] = new ReentrantLock();
		}
	}
	
	
	
	//==============//
	// update queue //
	//==============//
	
	/**
	 * Writing into memory is done synchronously so inputDataSource can 
	 * be closed after this method finishes.
	 */
	public CompletableFuture<Void> writeDataSourceToMemoryAndQueueSave(FullDataSourceV2 inputDataSource)
	{
		
		boolean saveNow = true;
		if (saveNow)
		{
			// TODO this doesn't leak, but also doesn't delay the save any
			FullDataSourceV2 memoryDataSource = FullDataSourceV2.createEmpty(inputDataSource.getPos());
			memoryDataSource.update(inputDataSource);
			return this.onSaveTimeoutAsyncFunc.saveAsync(memoryDataSource)
				.handle((voidObj, exception) -> 
				{
					memoryDataSource.close();
					return null;
				});
		}
		else
		{
			long dataSourcePos = inputDataSource.getPos();
			
			CompletableFuture<Void> future = this.futureBySectionPos.computeIfAbsent(dataSourcePos, (inputPos) -> new CompletableFuture<>());
			
			this.dataSourceByPosition.compute(dataSourcePos, (inputPos, memoryDataSource) ->
			{
				if (memoryDataSource == null)
				{
					// should not be closed since it will be used by other threads 
					memoryDataSource = FullDataSourceV2.createEmpty(inputPos);
				}
				memoryDataSource.update(inputDataSource);
				
				
				TimerTask timerTask = new TimerTask()
				{
					@Override
					public void run()
					{
						DelayedFullDataSourceSaveCache.this.saveTimerTasksBySectionPos.remove(dataSourcePos);
						
						try
						{
							FullDataSourceV2 dataSourceToSave = DelayedFullDataSourceSaveCache.this.dataSourceByPosition.remove(dataSourcePos);
							if (dataSourceToSave != null)
							{
								DelayedFullDataSourceSaveCache.this.onSaveTimeoutAsyncFunc.saveAsync(dataSourceToSave);
							}
						}
						catch (Exception e) // this can throw errors (not exceptions) when installed in Iris' dev environment for some reason due to an issue with LZ4's compression library
						{
							LOGGER.error("Failed to save updated data for section ["+dataSourcePos+"], error: ["+e.getMessage()+"]", e);
						}
						finally
						{
							CompletableFuture<Void> future = DelayedFullDataSourceSaveCache.this.futureBySectionPos.remove(dataSourcePos);
							if (future != null)
							{
								future.complete(null);
							}
						}
					}
				};
				try
				{
					DELAY_UPDATE_TIMER.schedule(timerTask, this.saveDelayInMs);
				}
				catch (IllegalStateException ignore)
				{
					// James isn't sure why this is possible since this logic is inside a lock, 
					// maybe the timer is just async enough that there can be problems?
					//LOGGER.warn("Attempted to queue an already canceled task. Pos: ["+dataSourcePos+"], task already queued for pos: ["+this.saveTimerTasksBySectionPos.containsKey(dataSourcePos)+"]");
				}
				
				
				// cancel the old save timer if present
				// (this is equivalent to restarting the timer)
				TimerTask oldTask = this.saveTimerTasksBySectionPos.put(dataSourcePos, timerTask);
				if (oldTask != null)
				{
					oldTask.cancel();
				}
				
				return memoryDataSource;
			});
			
			return future;
		}
	}
	
	public int getUnsavedCount() { return this.dataSourceByPosition.size(); }
	
	public void flush()
	{
		this.saveTimerTasksBySectionPos.forEach((pos, timerTask)-> 
		{
			timerTask.run();
		});
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
