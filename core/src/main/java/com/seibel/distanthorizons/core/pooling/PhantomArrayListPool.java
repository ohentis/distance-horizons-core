package com.seibel.distanthorizons.core.pooling;

import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.coreapi.util.StringUtil;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * DH uses a lot of potentially large arrays of {@link Byte}s and {@link Long}s.
 * In order to reduce Garbage Collector (GC) stuttering and array allocation overhead
 * we pool these arrays when possible. <br><br>
 * 
 * How pooled arrays can be returned: <br>
 * 1. <b> Closing the {@link PhantomArrayListParent} </b> <br>
 * The fastest and most efficient method of returning pooled arrays
 * is to call {@link AutoCloseable#close()}. <br><br>
 * 
 * 2. <b> {@link PhantomArrayListParent} Garbage Collection </b> <br>
 * Some objects are used across many different threads and
 * cleanly closing them is impossible, so when the {@link PhantomArrayListParent}
 * is automatically garbage collected we recover and recycle any
 * arrays it checked out.
 * This is less efficient since it may allow a lot of additional arrays to
 * be created while we wait for the garbage collector to run, but 
 * does prevent any leaks from {@link PhantomArrayListParent} that weren't closed.
 */
public class PhantomArrayListPool
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	/** 
	 * the recycler thread needs to be triggered relatively often to prevent
	 * build up of GC'ed arrays.
	 */
	private static final int PHANTOM_REF_CHECK_TIME_IN_MS = 1_000;
	private static final ThreadPoolExecutor RECYCLER_THREAD = ThreadUtil.makeSingleDaemonThreadPool("Phantom Array Recycler");
	private static final ArrayList<PhantomArrayListPool> POOL_LIST = new ArrayList<>();
	
	/** if enabled the number of GC'ed arrays will be logged */
	private static final boolean LOG_ARRAY_RECOVERY = false;
	
	
	private static boolean lowMemoryWarningLogged = false; 
	
	
	
	/** used for debugging and tracking what the pool contains */
	public final String name;
	
	public final ConcurrentHashMap<Reference<? extends PhantomArrayListParent>, PhantomArrayListCheckout>
			phantomRefToCheckout = new ConcurrentHashMap<>();
	public final ReferenceQueue<PhantomArrayListParent> phantomRefQueue = new ReferenceQueue<>();
	
	
	
	
	private final ConcurrentLinkedQueue<ByteArrayList> pooledByteArrays = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<ShortArrayList> pooledShortArrays = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<SoftReference<LongArrayList>> pooledLongArrays = new ConcurrentLinkedQueue<>();
	
	/** counts how many byte arrays have been created by this pool */
	private final AtomicInteger totalByteArrayCountRef = new AtomicInteger(0);
	/** counts how many short arrays have been created by this pool */
	private final AtomicInteger totalShortArrayCountRef = new AtomicInteger(0);
	/** counts how many long arrays have been created by this pool */
	private final AtomicInteger totalLongArrayCountRef = new AtomicInteger(0);
	
	/** used for debugging, represents an estimate for how many bytes the byte[] pool contains */
	private long lastBytePoolSizeInBytes = -1;
	/** used for debugging, represents an estimate for how many bytes the short[] pool contains */
	private long lastShortPoolSizeInBytes = -1;
	/** used for debugging, represents an estimate for how many bytes the long[] pool contains */
	private long lastLongPoolSizeInBytes = -1;
	
	/** For pools backed by {@link SoftReference}'s we may need to decrease the size when elements are garbage collected */
	private boolean clearLastRefPoolSizes = false;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	// shared setup used by all pools
	static
	{
		RECYCLER_THREAD.execute(() -> runPhantomReferenceCleanupLoop());
	}
	private static void runPhantomReferenceCleanupLoop()
	{
		while (true)
		{
			try
			{
				try
				{
					Thread.sleep(PHANTOM_REF_CHECK_TIME_IN_MS);
				}
				catch (InterruptedException ignore) { }
				
				
				for (int i = 0; i < POOL_LIST.size(); i++)
				{
					PhantomArrayListPool pool = POOL_LIST.get(i);
					
					int returnedByteArrayCount = 0;
					int returnedShortArrayCount = 0;
					int returnedLongArrayCount = 0;
					Reference<? extends PhantomArrayListParent> phantomRef = pool.phantomRefQueue.poll();
					while (phantomRef != null)
					{
						// return the pooled arrays
						PhantomArrayListCheckout checkout = pool.phantomRefToCheckout.remove(phantomRef);
						if (checkout != null)
						{
							returnedByteArrayCount += checkout.getByteArrayCount();
							returnedShortArrayCount += checkout.getShortArrayCount();
							returnedLongArrayCount += checkout.getLongArrayCount();
							pool.returnCheckout(checkout);
						}
						else
						{
							// shouldn't happen, but just in case
							LOGGER.warn("Pool: ["+pool.name+"]. Unable to find checkout for phantom reference ["+phantomRef+"], arrays will need to be recreated.");
						}
						
						phantomRef = pool.phantomRefQueue.poll();
					}
					
					if (LOG_ARRAY_RECOVERY)
					{
						if (returnedByteArrayCount != 0
								&& returnedShortArrayCount != 0
								&& returnedLongArrayCount != 0)
						{
							// we only want to log when arrays have been returned
							LOGGER.info("Pool: ["+pool.name+"]. Returned byte:["+F3Screen.NUMBER_FORMAT.format(returnedByteArrayCount)+"], short:["+F3Screen.NUMBER_FORMAT.format(returnedShortArrayCount)+"], long:["+F3Screen.NUMBER_FORMAT.format(returnedLongArrayCount)+"].");
						}
					}
					
					// since this is just for debugging it only needs to be recalculated once in a while
					pool.recalculateSizeForDebugging();
				}
			}
			catch (Exception e)
			{
				LOGGER.error("Unexpected error in phantom pool return thread, error: [" + e.getMessage() + "].", e);
			}
		}
	}
	
	
	public PhantomArrayListPool(String name)
	{
		POOL_LIST.add(this);
		this.name = name;
	}
	
	
	
	
	
	//==============//
	// get checkout //
	//==============//
	
	public PhantomArrayListCheckout checkoutArrays(int byteArrayCount, int shortArrayCount, int longArrayCount)
	{
		PhantomArrayListCheckout checkout = new PhantomArrayListCheckout(this);
		
		// byte
		for (int i = 0; i < byteArrayCount; i++)
		{
			checkout.addByteArrayList(getPooledArray(this.pooledByteArrays, () -> this.createEmptyByteArrayList()));
		}
		
		// short
		for (int i = 0; i < shortArrayCount; i++)
		{
			checkout.addShortArrayList(getPooledArray(this.pooledShortArrays, () -> this.createEmptyShortArrayList()));
		}
		
		// long
		for (int i = 0; i < longArrayCount; i++)
		{
			addRefPooledArray(
					this.pooledLongArrays,
					this::createEmptyLongArrayList,
					this::onLongArrayListGarbageCollected,
					checkout::addLongArrayListRef);
		}
		
		return checkout;
	}
	
	
	// array constructors //
	
	private ByteArrayList createEmptyByteArrayList()
	{
		//LOGGER.error("created new byte array");
		this.totalByteArrayCountRef.getAndIncrement();
		return new ByteArrayList(0);
	}
	private ShortArrayList createEmptyShortArrayList()
	{
		//LOGGER.error("created new short array");
		this.totalShortArrayCountRef.getAndIncrement();
		return new ShortArrayList(0);
	}
	private LongArrayList createEmptyLongArrayList()
	{
		//LOGGER.error("created new long array");
		this.totalLongArrayCountRef.getAndIncrement();
		return new LongArrayList(0);
	}
	
	
	// garbage collection handlers //
	
	/** should only happen if Java doesn't have enough memory */
	private void onLongArrayListGarbageCollected()
	{
		this.clearLastRefPoolSizes = true;
		this.totalLongArrayCountRef.getAndDecrement();
	}
	
	
	// internal pool handlers //
	
	private static <T extends List<?>> T getPooledArray(ConcurrentLinkedQueue<T> pool, Supplier<T> emptyArrayCreatorFunc)
	{
		T array = pool.poll();
		if (array != null)
		{
			array.clear();
			return array;
		}
		else
		{
			// no pooled sources exist
			return emptyArrayCreatorFunc.get();
		}
	}
	private static <T extends List<?>> void addRefPooledArray(
			ConcurrentLinkedQueue<SoftReference<T>> arrayPool, 
			Supplier<T> emptyArrayCreatorFunc,
			Runnable arrayGarbageCollectedFunc,
			BiConsumer<T, SoftReference<T>> putArrayFunc)
	{
		T array = null;
		SoftReference<T> arrayRef = arrayPool.poll();
		
		// find the first non-null pooled array
		while (arrayRef != null && array == null)
		{
			array = arrayRef.get();
			if (array == null)
			{
				// this reference is pointing to null,
				// the array must have been garbage collected,
				// that means we don't have enough memory
				if (!lowMemoryWarningLogged)
				{
					lowMemoryWarningLogged = true;
					
					// orange text
					String message = "\u00A76" + "Distant Horizons: Insufficient memory detected." + "\u00A7r \n" +
							"This may cause stuttering or crashing. \n" +
							"Either: your allocated memory isn't high enough, \n" +
							"your DH CPU preset is too high, or your DH quality preset is too high.";
					
					LOGGER.warn(message);
					if (Config.Common.Logging.Warning.showPoolInsufficientMemoryWarning.get())
					{
						ClientApi.INSTANCE.showChatMessageNextFrame(message);
					}
				}
				
				arrayGarbageCollectedFunc.run();
				
				// try the next reference
				arrayRef = arrayPool.poll();
			}
		}
		
		
		if (array != null)
		{
			LodUtil.assertTrue(arrayRef != null, "How did we get an array without it's reference?");
			array.clear();
		}
		else
		{
			// no pooled sources exist
			array = emptyArrayCreatorFunc.get();
			arrayRef = new SoftReference<>(array);
		}
		
		putArrayFunc.accept(array, arrayRef);
	}
	
	
	
	//=================//
	// return checkout //
	//=================//
	
	public void returnCheckout(PhantomArrayListCheckout checkout)
	{
		if (checkout == null)
		{
			throw new IllegalArgumentException("Null phantom checkout, memory leak in progress...");
		}
		
		
		// In James' testing pooling the checkout object wasn't necessary
		// since it is relatively small and short lived, thus
		// the GC can handle quickly discarding it.
		
		this.pooledByteArrays.addAll(checkout.getAllByteArrays());
		this.pooledShortArrays.addAll(checkout.getAllShortArrays());
		this.pooledLongArrays.addAll(checkout.getAllLongArrayRefs());
		
		//LOGGER.info("Returned ["+checkout.byteArrayLists.size()+"/"+this.pooledByteArrays.size()+"] bytes and ["+checkout.longArrayLists.size()+"/"+this.pooledLongArrays.size()+"] longs.");\
	}

	
	
	//===============//
	// debug methods //
	//===============//
	
	public static void addDebugMenuStringsToListForCombinedPools(List<String> messageList)
	{
		int totalByteArrayCount = 0, totalShortArrayCount = 0, totalLongArrayCount = 0;
		int pooledByteArraySize = 0, pooledShortArraySize = 0, pooledLongArraySize = 0;
		long lastBytePoolSizeInBytes = 0, lastShortPoolSizeInBytes = 0, lastLongPoolSizeInBytes = 0;
		
		for (int i = 0; i < POOL_LIST.size(); i++)
		{
			PhantomArrayListPool pool = POOL_LIST.get(i);
			
			totalByteArrayCount += pool.totalByteArrayCountRef.get();
			totalShortArrayCount += pool.totalShortArrayCountRef.get();
			totalLongArrayCount += pool.totalLongArrayCountRef.get();
			
			pooledByteArraySize += pool.pooledByteArrays.size();
			pooledShortArraySize += pool.pooledShortArrays.size();
			pooledLongArraySize += pool.pooledLongArrays.size();
			
			lastBytePoolSizeInBytes += pool.lastBytePoolSizeInBytes;
			lastShortPoolSizeInBytes += pool.lastShortPoolSizeInBytes;
			lastLongPoolSizeInBytes += pool.lastLongPoolSizeInBytes;
		}
		
		addDebugMenuStringsToList(messageList,
			"Combined",
			totalByteArrayCount, totalShortArrayCount, totalLongArrayCount,
			pooledByteArraySize, pooledShortArraySize, pooledLongArraySize,
			lastBytePoolSizeInBytes, lastShortPoolSizeInBytes, lastLongPoolSizeInBytes
		);
	}
	
	public static void addDebugMenuStringsToListForSeparatePools(List<String> messageList)
	{
		for (int i = 0; i < POOL_LIST.size(); i++)
		{
			PhantomArrayListPool pool = POOL_LIST.get(i);
			pool.addDebugMenuStringsToList(messageList);
		}
	}
	
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		addDebugMenuStringsToList(messageList,
			this.name,
			this.totalByteArrayCountRef.get(), this.totalShortArrayCountRef.get(), this.totalLongArrayCountRef.get(),
			this.pooledByteArrays.size(), this.pooledShortArrays.size(), this.pooledLongArrays.size(),
			this.lastBytePoolSizeInBytes, this.lastShortPoolSizeInBytes, this.lastLongPoolSizeInBytes
		);
	}
	private static void addDebugMenuStringsToList(List<String> messageList, 
			String name,
			int totalByteArrayCount, int totalShortArrayCount, int totalLongArrayCount,
			int numbOfByteArraysInPool, int numbOfShortArraysInPool, int numbOfLongArraysInPool,
			long lastBytePoolSizeInBytes, long lastShortPoolSizeInBytes, long lastLongPoolSizeInBytes)
	{
		// total (all time created) count
		String byteArrayTotalCount = F3Screen.NUMBER_FORMAT.format(totalByteArrayCount);
		String shortArrayTotalCount = F3Screen.NUMBER_FORMAT.format(totalShortArrayCount);
		String longArrayTotalCount = F3Screen.NUMBER_FORMAT.format(totalLongArrayCount);
		
		// inactive items in pool
		String bytePoolCount = F3Screen.NUMBER_FORMAT.format(numbOfByteArraysInPool);
		String shortPoolCount = F3Screen.NUMBER_FORMAT.format(numbOfShortArraysInPool);
		String longPoolCount = F3Screen.NUMBER_FORMAT.format(numbOfLongArraysInPool);
		
		// pool byte size
		String bytePoolSizeInBytes = (lastBytePoolSizeInBytes != -1)
				? " ~" + StringUtil.convertBytesToHumanReadable(lastBytePoolSizeInBytes)
				: "";
		String shortPoolSizeInBytes = (lastShortPoolSizeInBytes != -1)
				? " ~" + StringUtil.convertBytesToHumanReadable(lastShortPoolSizeInBytes)
				: "";
		String longPoolSizeInBytes = (lastLongPoolSizeInBytes != -1)
				? " ~" + StringUtil.convertBytesToHumanReadable(lastLongPoolSizeInBytes)
				: "";
		
		
		messageList.add(name + " - Pools:");
		if (totalByteArrayCount != 0)
		{
			messageList.add("byte[]: " + bytePoolCount + "/" + byteArrayTotalCount + bytePoolSizeInBytes);
		}
		if (totalShortArrayCount != 0)
		{
			messageList.add("short[]: " + shortPoolCount + "/" + shortArrayTotalCount + shortPoolSizeInBytes);
		}
		if (totalLongArrayCount != 0)
		{
			messageList.add("long[]: " + longPoolCount + "/" + longArrayTotalCount + longPoolSizeInBytes);
		}
	}
	
	
	/**
	 *  shouldn't be called on the render thread as it can
	 *  take 10's of milliseconds to complete.
	 */
	public void recalculateSizeForDebugging()
	{
		// byte
		long bytePoolByteSize = estimateMemoryUsage(this.pooledByteArrays, Byte.BYTES);
		this.lastBytePoolSizeInBytes = Math.max(bytePoolByteSize, this.lastBytePoolSizeInBytes);
		
		// short
		long shortPoolByteSize = estimateMemoryUsage(this.pooledShortArrays, Short.BYTES);
		this.lastShortPoolSizeInBytes = Math.max(shortPoolByteSize, this.lastShortPoolSizeInBytes);
		
		// long
		if (this.clearLastRefPoolSizes)
		{
			this.lastLongPoolSizeInBytes = 0;
			this.clearLastRefPoolSizes = false;
		}
		long longPoolByteSize = estimateRefMemoryUsage(this.pooledLongArrays, Long.BYTES);
		this.lastLongPoolSizeInBytes = Math.max(longPoolByteSize, this.lastLongPoolSizeInBytes);
	}
	
	private static <T extends Collection<?>> long estimateMemoryUsage(ConcurrentLinkedQueue<T> pool, long elementSizeInBytes)
	{
		long longByteSize = 0;
		for (T array : pool)
		{
			// Object overhead + capacity of underlying array * size of Long (8 bytes)
			long overhead = Byte.SIZE * 4;
			
			long elementCount = getCollectionCount(array);
			long arraySize = elementCount * elementSizeInBytes;
			longByteSize += overhead + arraySize;
		}
		return longByteSize;
	}
	private static <T extends Collection<?>> long estimateRefMemoryUsage(ConcurrentLinkedQueue<SoftReference<T>> pool, long elementSizeInBytes)
	{
		long longByteSize = 0;
		for (SoftReference<T> arrayRef : pool)
		{
			// Object overhead + capacity of underlying array * size of Long (8 bytes)
			long overhead = Byte.SIZE * 4;
			T array = arrayRef.get();
			if (array == null)
			{
				continue;
			}
			
			long elementCount = getCollectionCount(array);
			long arraySize = elementCount * elementSizeInBytes;
			longByteSize += overhead + arraySize;
		}
		return longByteSize;
	}
	private static long getCollectionCount(@NotNull Collection<?> array)
	{
		long elementCount;
		if (array instanceof ByteArrayList)
		{
			elementCount = ((ByteArrayList)array).elements().length;
		}
		else if (array instanceof ShortArrayList)
		{
			elementCount = ((ShortArrayList)array).elements().length;
		}
		else if (array instanceof LongArrayList)
		{
			elementCount = ((LongArrayList)array).elements().length;
		}
		else
		{
			throw new UnsupportedOperationException("Not implemented for type ["+array.getClass().getSimpleName()+"].");
		}
		
		return elementCount;
	}
	
	
	
}
