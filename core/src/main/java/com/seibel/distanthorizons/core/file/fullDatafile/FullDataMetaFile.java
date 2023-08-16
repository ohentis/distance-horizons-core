package com.seibel.distanthorizons.core.file.fullDatafile;

import java.awt.*;
import java.io.*;
import java.lang.ref.*;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileAlreadyExistsException;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.file.metaData.AbstractMetaDataContainerFile;
import com.seibel.distanthorizons.core.file.metaData.BaseMetaData;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.dataObjects.fullData.loader.AbstractFullDataSourceLoader;
import com.seibel.distanthorizons.core.util.AtomicsUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import org.apache.logging.log4j.Logger;

/**
 * Represents a File that contains a {@link IFullDataSource}.
 */
public class FullDataMetaFile extends AbstractMetaDataContainerFile implements IDebugRenderable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger(FullDataMetaFile.class.getSimpleName());
	
	private final IDhLevel level;
	private final IFullDataSourceProvider fullDataSourceProvider;
	public boolean doesFileExist;
	
	//TODO: Atm can't find a better way to store when genQueue is checked.
	public boolean genQueueChecked = false;
	
	private volatile boolean markedNeedUpdate = false;
	
	public AbstractFullDataSourceLoader fullDataSourceLoader;
	public Class<? extends IFullDataSource> dataType;
	
	/**
	 * Can be cleared if the garbage collector determines there isn't enough space. <br><br>
	 *
	 * When clearing, don't set to null, instead create a SoftReference containing null.
	 * This will make null checks simpler.
	 */
	private SoftReference<IFullDataSource> cachedFullDataSource = new SoftReference<>(null);
	private final AtomicReference<CompletableFuture<IFullDataSource>> dataSourceLoadFutureRef = new AtomicReference<>(null);
	
	private static final class CacheQueryResult
	{
		public final CompletableFuture<IFullDataSource> future;
		public final boolean needsLoad;
		public CacheQueryResult(CompletableFuture<IFullDataSource> future, boolean needsLoad)
		{
			this.future = future;
			this.needsLoad = needsLoad;
		}
		
	}
	
	@Override
	public void debugRender(DebugRenderer r)
	{
		if (pos.sectionDetailLevel > DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL) return;
		
		IFullDataSource cached = cachedFullDataSource.get();
		if (markedNeedUpdate)
			r.renderBox(new DebugRenderer.Box(pos, 80f, 96f, 0.05f, Color.red));
		
		Color c = Color.black;
		if (cached != null)
		{
			if (cached instanceof CompleteFullDataSource)
			{
				c = Color.GREEN;
			}
			else
			{
				c = Color.YELLOW;
			}
			
		}
		else if (dataSourceLoadFutureRef.get() != null)
		{
			c = Color.BLUE;
		}
		else if (doesFileExist)
		{
			c = Color.RED;
		}
		boolean needUpdate = !this.writeQueueRef.get().queue.isEmpty() || markedNeedUpdate;
		if (needUpdate) c = c.darker().darker();
		r.renderBox(new DebugRenderer.Box(pos, 80f, 96f, 0.05f, c));
	}
	
	//TODO: use ConcurrentAppendSingleSwapContainer<LodDataSource> instead of below:
	private static class GuardedMultiAppendQueue
	{
		ReentrantReadWriteLock appendLock = new ReentrantReadWriteLock();
		ConcurrentLinkedQueue<ChunkSizedFullDataAccessor> queue = new ConcurrentLinkedQueue<>();
		
	}
	
	
	// ===Concurrent Write stuff===
	private final AtomicReference<GuardedMultiAppendQueue> writeQueueRef = new AtomicReference<>(new GuardedMultiAppendQueue());
	private GuardedMultiAppendQueue backWriteQueue = new GuardedMultiAppendQueue();
	// ===========================
	
	// ===Object lifetime stuff===
	private static final ReferenceQueue<IFullDataSource> lifeCycleDebugQueue = new ReferenceQueue<>();
	private static final ReferenceQueue<IFullDataSource> softRefDebugQueue = new ReferenceQueue<>();
	private static final Set<DataObjTracker> lifeCycleDebugSet = ConcurrentHashMap.newKeySet();
	private static final Set<DataObjSoftTracker> softRefDebugSet = ConcurrentHashMap.newKeySet();
	
	private static class DataObjTracker extends PhantomReference<IFullDataSource> implements Closeable
	{
		public final DhSectionPos pos;
		DataObjTracker(IFullDataSource data)
		{
			super(data, lifeCycleDebugQueue);
			//LOGGER.info("Phantom created on {}! count: {}", data.getSectionPos(), lifeCycleDebugSet.size());
			lifeCycleDebugSet.add(this);
			this.pos = data.getSectionPos();
		}
		@Override
		public void close() { lifeCycleDebugSet.remove(this); }
		
	}
	
	private static class DataObjSoftTracker extends SoftReference<IFullDataSource> implements Closeable
	{
		public final FullDataMetaFile file;
		DataObjSoftTracker(FullDataMetaFile file, IFullDataSource data)
		{
			super(data, softRefDebugQueue);
			softRefDebugSet.add(this);
			this.file = file;
		}
		@Override
		public void close() { softRefDebugSet.remove(this); }
		
	}
	// ===========================
	
	
	
	//==============//
	// constructors //
	//==============//
	
	/**
	 * Creates a new file.
	 *
	 * @throws FileAlreadyExistsException if a file already exists.
	 */
	public FullDataMetaFile(IFullDataSourceProvider fullDataSourceProvider, IDhLevel level, DhSectionPos pos) throws FileAlreadyExistsException
	{
		super(fullDataSourceProvider.computeDataFilePath(pos), pos);
		debugPhantomLifeCycleCheck();
		
		this.fullDataSourceProvider = fullDataSourceProvider;
		this.level = level;
		LodUtil.assertTrue(this.baseMetaData == null);
		this.doesFileExist = false;
		DebugRenderer.register(this);
	}
	
	/**
	 * Uses an existing file.
	 *
	 * @throws IOException if the file was formatted incorrectly
	 * @throws FileNotFoundException if no file exists for the given path
	 */
	public FullDataMetaFile(IFullDataSourceProvider fullDataSourceProvider, IDhLevel level, File file) throws IOException, FileNotFoundException
	{
		super(file);
		debugPhantomLifeCycleCheck();
		
		this.fullDataSourceProvider = fullDataSourceProvider;
		this.level = level;
		LodUtil.assertTrue(this.baseMetaData != null);
		this.doesFileExist = true;
		
		this.fullDataSourceLoader = AbstractFullDataSourceLoader.getLoader(this.baseMetaData.dataTypeId, this.baseMetaData.binaryDataFormatVersion);
		if (this.fullDataSourceLoader == null)
		{
			// TODO add a hard coded dictionary of known ID name combos so we can easily see in the log if the ID is valid or if the data was corrupted/old
			throw new IOException("Invalid file: Data type loader not found: " + this.baseMetaData.dataTypeId + "(v" + this.baseMetaData.binaryDataFormatVersion + ")");
		}
		
		this.dataType = this.fullDataSourceLoader.clazz;
		DebugRenderer.register(this);
	}
	
	public void markNeedUpdate() { this.markedNeedUpdate = true; }
	
	//==========//
	// get data //
	//==========//
	
	// Try get cached data source. Used for temp impl for re-queueing world gen tasks.
	// (Read-only access! As writes should always be done async)
	public IFullDataSource getCachedDataSourceNowOrNull()
	{
		debugPhantomLifeCycleCheck();
		return this.cachedFullDataSource.get();
	}
	
	private void makeUpdateCompletionStage(CompletableFuture<IFullDataSource> completer, CompletableFuture<IFullDataSource> currentStage)
	{
		currentStage.thenCompose(
						(fullDataSource) -> {
							markedNeedUpdate = false;
							return this.fullDataSourceProvider.onDataFileUpdate(fullDataSource, this, this::_updateAndWriteDataSource, this::_applyWriteQueueToFullDataSource);
						})
				.whenComplete((fullDataSource, ex) ->
				{
					if (ex != null && !LodUtil.isInterruptOrReject(ex))
					{
						LOGGER.error("Error updating file [" + this.file + "]: ", ex);
					}
					
					if (fullDataSource != null)
					{
						new DataObjTracker(fullDataSource);
						new DataObjSoftTracker(this, fullDataSource);
					}
					//LOGGER.info("Updated file "+this.file);
					if (pos.sectionDetailLevel == DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL)
						DebugRenderer.makeParticle(
								new DebugRenderer.BoxParticle(
										new DebugRenderer.Box(this.pos, 64f, 72f, 0.03f, Color.green.darker()),
										0.2, 32f
								)
						);
					
					this.cachedFullDataSource = new SoftReference<>(fullDataSource);
					inCrit = false;
					dataSourceLoadFutureRef.set(null);
					completer.complete(fullDataSource);
					
					if (this.markedNeedUpdate)
					{
						// trigger another update
						this.loadOrGetCachedDataSourceAsync();
					}
				});
	}
	
	private void makeLoadCompletionStage(ExecutorService executorService, CompletableFuture<IFullDataSource> completer)
	{
		makeUpdateCompletionStage(completer, CompletableFuture.supplyAsync(() -> {
			// Load the file.
			IFullDataSource fullDataSource;
			try (FileInputStream fileInputStream = this.getFileInputStream();
					DhDataInputStream compressedStream = new DhDataInputStream(fileInputStream))
			{
				fullDataSource = this.fullDataSourceLoader.loadData(this, compressedStream, this.level);
			}
			catch (Exception ex)
			{
				// can happen if there is a missing file or the file was incorrectly formatted, or terminated early
				throw new CompletionException(ex);
			}
			return fullDataSource;
		}, executorService));
	}
	
	private void makeCreateCompletionStage(CompletableFuture<IFullDataSource> completer)
	{
		this.makeUpdateCompletionStage(completer, this.fullDataSourceProvider.onCreateDataFile(this)
				.thenApply((fullDataSource) ->
				{
					this.baseMetaData = this._makeBaseMetaData(fullDataSource);
					return fullDataSource;
				}));
	}
	
	private volatile boolean inCrit = false;
	// Cause: Generic Type runtime casting cannot safety check it.
	// However, the Union type ensures the 'data' should only contain the listed type.
	public CompletableFuture<IFullDataSource> loadOrGetCachedDataSourceAsync()
	{
		debugPhantomLifeCycleCheck();
		
		CacheQueryResult result = this.getCachedDataSourceAsync();
		
		if (result.needsLoad)
		{
			LodUtil.assertTrue(!this.inCrit);
			this.inCrit = true;
			
			CompletableFuture<IFullDataSource> future = result.future;
			// don't continue if the provider has been shut down
			ExecutorService executorService = this.fullDataSourceProvider.getIOExecutor();
			if (executorService.isTerminated())
			{
				this.inCrit = false;
				this.dataSourceLoadFutureRef.set(null);
				future.complete(null);
				return future;
			}
			
			// create a new Meta file
			if (!this.doesFileExist)
			{
				this.makeCreateCompletionStage(future);
			}
			else
			{
				// Otherwise, load and update file
				if (this.baseMetaData == null)
				{
					throw new IllegalStateException("Meta data not loaded!");
				}
				
				this.makeLoadCompletionStage(executorService, future);
			}
		}
		
		return result.future;
	}
	
	/** @return a stream for the data contained in this file, skips the metadata from {@link AbstractMetaDataContainerFile}. */
	private FileInputStream getFileInputStream() throws IOException
	{
		FileInputStream fileInputStream = new FileInputStream(this.file);
		
		// skip the meta-data bytes
		int bytesToSkip = AbstractMetaDataContainerFile.METADATA_SIZE_IN_BYTES;
		while (bytesToSkip > 0)
		{
			long skippedByteCount = fileInputStream.skip(bytesToSkip);
			if (skippedByteCount == 0)
			{
				throw new IOException("Invalid file: Failed to skip metadata.");
			}
			bytesToSkip -= skippedByteCount;
		}
		
		if (bytesToSkip != 0)
		{
			throw new IOException("File IO Error: Failed to skip metadata.");
		}
		return fileInputStream;
	}
	private BaseMetaData _makeBaseMetaData(IFullDataSource data)
	{
		AbstractFullDataSourceLoader loader = AbstractFullDataSourceLoader.getLoader(data.getClass(), data.getBinaryDataFormatVersion());
		return new BaseMetaData(data.getSectionPos(), -1,
				data.getDataDetailLevel(), data.getWorldGenStep(), (loader == null ? 0 : loader.datatypeId), data.getBinaryDataFormatVersion());
	}
	
	/**
	 * @return one of the following:
	 * the cached {@link IFullDataSource},
	 * a future that will complete once the {@link FullDataMetaFile#writeQueueRef} has been written,
	 * or null if nothing has been cached and nothing is being loaded
	 */
	private CacheQueryResult getCachedDataSourceAsync()
	{
		// this data source is being written to, use the existing future
		CompletableFuture<IFullDataSource> dataSourceLoadFuture = this.dataSourceLoadFutureRef.get();
		if (dataSourceLoadFuture != null)
		{
			return new CacheQueryResult(dataSourceLoadFuture, false);
		}
		// attempt to get the cached data source
		IFullDataSource cachedFullDataSource = this.cachedFullDataSource.get();
		if (cachedFullDataSource == null)
		{
			// Make a new future, and CAS it into the dataSourceLoadFutureRef, or return the existing future
			CompletableFuture<IFullDataSource> newFuture = new CompletableFuture<>();
			CompletableFuture<IFullDataSource> cas = AtomicsUtil.compareAndExchange(dataSourceLoadFutureRef, null, newFuture);
			if (cas == null)
			{
				return new CacheQueryResult(newFuture, true);
			}
			else
			{
				return new CacheQueryResult(cas, false);
			}
		}
		else
		{
			// The file is cached in RAM
			boolean needUpdate = !this.writeQueueRef.get().queue.isEmpty() || markedNeedUpdate;
			
			if (!needUpdate)
			{
				// return the cached data
				return new CacheQueryResult(CompletableFuture.completedFuture(cachedFullDataSource), false);
			}
			else
			{
				// either write the queue or return the future that is waiting for the queue write
				
				// Do a CAS on inCacheWriteLock to ensure that we are the only thread that is writing to the cache,
				// or if we fail, then that means someone else is already doing it, and we can just return the future
				CompletableFuture<IFullDataSource> future = new CompletableFuture<>();
				CompletableFuture<IFullDataSource> compareAndSwapFuture = AtomicsUtil.compareAndExchange(dataSourceLoadFutureRef, null, future);
				if (compareAndSwapFuture != null)
				{
					// a write is already in progress, return its future.
					return new CacheQueryResult(compareAndSwapFuture, false);
				}
				else
				{
					LodUtil.assertTrue(!inCrit);
					inCrit = true;
					// don't continue if the provider has been shut down
					ExecutorService executorService = this.fullDataSourceProvider.getIOExecutor();
					if (executorService.isTerminated())
					{
						inCrit = false;
						dataSourceLoadFutureRef.set(null);
						future.complete(null);
					}
					else
					{
						// write the queue to the data source by triggering an update
						makeUpdateCompletionStage(future, CompletableFuture.supplyAsync(() -> cachedFullDataSource, executorService));
					}
					return new CacheQueryResult(future, false);
				}
			}
		}
	}
	
	
	
	//===============//
	// data updating //
	//===============//
	
	/**
	 * Adds the given {@link ChunkSizedFullDataAccessor} to the write queue,
	 * which will be applied to the object at some undefined time in the future.
	 */
	public void addToWriteQueue(ChunkSizedFullDataAccessor chunkAccessor)
	{
		debugPhantomLifeCycleCheck();
		
		DhLodPos chunkLodPos = new DhLodPos(LodUtil.CHUNK_DETAIL_LEVEL, chunkAccessor.pos.x, chunkAccessor.pos.z);
		
		LodUtil.assertTrue(this.pos.getSectionBBoxPos().overlapsExactly(chunkLodPos), "Chunk pos " + chunkLodPos + " doesn't exactly overlap with section " + this.pos);
		//LOGGER.info("Write Chunk {} to file {}", chunkPos, pos);
		
		GuardedMultiAppendQueue writeQueue = this.writeQueueRef.get();
		// Using read lock is OK, because the queue's underlying data structure is thread-safe.
		// This lock is only used to insure on polling the queue, that the queue is not being
		// modified by another thread.
		ReentrantReadWriteLock.ReadLock appendLock = writeQueue.appendLock.readLock();
		appendLock.lock();
		try
		{
			writeQueue.queue.add(chunkAccessor);
		}
		finally
		{
			appendLock.unlock();
		}
		
		this.flushAndSaveAsync();
		//LOGGER.info("write queue length for pos "+this.pos+": " + writeQueue.queue.size());
	}
	
	
	/** Applies any queued {@link ChunkSizedFullDataAccessor} to this metadata's {@link IFullDataSource} and writes the data to file. */
	public CompletableFuture<Void> flushAndSaveAsync()
	{
		debugPhantomLifeCycleCheck();
		boolean isEmpty = this.writeQueueRef.get().queue.isEmpty() && !markedNeedUpdate;
		if (!isEmpty)
		{
			// This will flush the data to disk.
			return this.loadOrGetCachedDataSourceAsync().thenApply((fullDataSource) -> null /* ignore the result, just wait for the load to finish*/ );
		}
		else
		{
			return CompletableFuture.completedFuture(null);
		}
	}
	
	
	/** updates this object to match the given {@link IFullDataSource} and then writes the new data to file. */
	private void _updateAndWriteDataSource(IFullDataSource fullDataSource)
	{
		if (fullDataSource.isEmpty())
		{
			// delete the empty data source
			if (this.file.exists() && !this.file.delete())
			{
				LOGGER.warn("Failed to delete data file at " + this.file);
			}
			this.doesFileExist = false;
		}
		else
		{
			// update the data source and write the new data to file
			
			//LOGGER.info("Saving data file of {}", data.getSectionPos());
			try
			{
				// Write/Update data
				LodUtil.assertTrue(this.baseMetaData != null);
				
				this.baseMetaData.dataLevel = fullDataSource.getDataDetailLevel();
				this.fullDataSourceLoader = AbstractFullDataSourceLoader.getLoader(fullDataSource.getClass(), fullDataSource.getBinaryDataFormatVersion());
				LodUtil.assertTrue(this.fullDataSourceLoader != null, "No loader for " + fullDataSource.getClass() + " (v" + fullDataSource.getBinaryDataFormatVersion() + ")");
				
				this.dataType = fullDataSource.getClass();
				this.baseMetaData.dataTypeId = (this.fullDataSourceLoader == null) ? 0 : this.fullDataSourceLoader.datatypeId;
				this.baseMetaData.binaryDataFormatVersion = fullDataSource.getBinaryDataFormatVersion();
				
				super.writeData((bufferedOutputStream) -> fullDataSource.writeToStream((bufferedOutputStream), this.level));
				this.doesFileExist = true;
			}
			catch (ClosedByInterruptException e) // thrown by buffers that are interrupted
			{
				// expected if the file handler is shut down, the exception can be ignored
//				LOGGER.warn("FullData file writing interrupted.", e);
			}
			catch (IOException e)
			{
				LOGGER.error("Failed to save updated data file at " + this.file + " for section " + this.pos, e);
			}
		}
	}
	
	/** @return true if the queue was not empty and data was applied to the {@link IFullDataSource}. */
	private boolean _applyWriteQueueToFullDataSource(IFullDataSource fullDataSource)
	{
		// Poll the write queue
		// First check if write queue is empty, then swap the write queue.
		// Must be done in this order to ensure isMemoryAddressValid work properly. See isMemoryAddressValid() for details.
		boolean isEmpty = this.writeQueueRef.get().queue.isEmpty();
		if (!isEmpty)
		{
			this._swapWriteQueue();
			for (ChunkSizedFullDataAccessor chunk : this.backWriteQueue.queue)
			{
				fullDataSource.update(chunk);
			}
			this.backWriteQueue.queue.clear();
			//LOGGER.info("Updated Data file at {} for sect {} with {} chunk writes.", path, pos, count);
		}
		return !isEmpty || !doesFileExist;
	}
	private void _swapWriteQueue()
	{
		GuardedMultiAppendQueue writeQueue = this.writeQueueRef.getAndSet(this.backWriteQueue);
		// Acquire write lock and then release it again as we only need to ensure that the queue
		// is not being appended to by another thread. Note that the above atomic swap &
		// the guarantee that all append first acquire the appendLock means after the locK() call,
		// there will be no other threads able to or is currently appending to the queue.
		// Note: The above needs the getAndSet() to have at least Release Memory order.
		// (not that java supports anything non volatile for getAndSet()...)
		writeQueue.appendLock.writeLock().lock();
		writeQueue.appendLock.writeLock().unlock();
		this.backWriteQueue = writeQueue;
	}
	
	
	
	//===========//
	// debugging //
	//===========//
	
	public static void debugPhantomLifeCycleCheck()
	{
		DataObjTracker phantom = (DataObjTracker) lifeCycleDebugQueue.poll();
		
		// wait for the tracker to be garbage collected(?)
		while (phantom != null)
		{
			//LOGGER.info("Full Data at pos: "+phantom.pos+" has been freed. "+lifeCycleDebugSet.size()+" Full Data files remaining.");
			phantom.close();
			phantom = (DataObjTracker) lifeCycleDebugQueue.poll();
		}
		
		DataObjSoftTracker soft = (DataObjSoftTracker) softRefDebugQueue.poll();
		while (soft != null)
		{
			//LOGGER.info("Full Data at pos: "+soft.file.pos+" has been soft released.");
			soft.close();
			soft = (DataObjSoftTracker) softRefDebugQueue.poll();
		}
	}
	
}
