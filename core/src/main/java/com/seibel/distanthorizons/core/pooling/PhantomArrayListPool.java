package com.seibel.distanthorizons.core.pooling;

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.coreapi.util.StringUtil;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
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
	
	public static final PhantomArrayListPool INSTANCE = new PhantomArrayListPool();
	
	
	/** needed since our pools aren't thread safe */
	private final ReentrantLock poolLock = new ReentrantLock();
	
	private final ArrayList<ByteArrayList> pooledByteArrays = new ArrayList<>();
	private final ArrayList<ShortArrayList> pooledShortArrays = new ArrayList<>();
	private final ArrayList<LongArrayList> pooledLongArrays = new ArrayList<>();
	
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
	
	
	
	//=============//
	// constructor //
	//=============//
	
	private PhantomArrayListPool()
	{
		// create initial arrays
		PhantomArrayListCheckout checkout = this.checkoutArrays(2_000, 100, 100_000);
		this.returnCheckout(checkout);
	}
	
	
	
	//==============//
	// get checkout //
	//==============//
	
	public PhantomArrayListCheckout checkoutArrays(int byteArrayCount, int shortArrayCount, int longArrayCount)
	{
		try
		{
			this.poolLock.lock();
			
			PhantomArrayListCheckout checkout = new PhantomArrayListCheckout();
			
			// byte
			for (int i = 0; i < byteArrayCount; i++)
			{
				checkout.addByteArrayList(getPooledArray(this.pooledByteArrays, this::createEmptyByteArrayList));
			}
			
			// short
			for (int i = 0; i < shortArrayCount; i++)
			{
				checkout.addShortArrayList(getPooledArray(this.pooledShortArrays, this::createEmptyShortArrayList));
			}
			
			// long
			for (int i = 0; i < longArrayCount; i++)
			{
				checkout.addLongArrayList(getPooledArray(this.pooledLongArrays, this::createEmptyLongArrayList));
			}
			
			return checkout;
		}
		finally
		{
			this.poolLock.unlock();
		}
	}
	
	
	// array constructors //
	
	private ByteArrayList createEmptyByteArrayList()
	{
		//LOGGER.error("created new byte array");
		this.totalByteArrayCountRef.getAndIncrement();
		return new ByteArrayList();
	}
	private ShortArrayList createEmptyShortArrayList()
	{
		//LOGGER.error("created new short array");
		this.totalShortArrayCountRef.getAndIncrement();
		return new ShortArrayList();
	}
	private LongArrayList createEmptyLongArrayList()
	{
		//LOGGER.error("created new long array");
		this.totalLongArrayCountRef.getAndIncrement();
		return new LongArrayList();
	}
	
	
	// internal pool handler //
	
	private static <T extends List<?>> T getPooledArray(ArrayList<T> pool, Supplier<T> emptyArrayCreatorFunc)
	{
		int index = pool.size() - 1;
		if (index == -1)
		{
			// no pooled sources exist
			return emptyArrayCreatorFunc.get();
		}
		else
		{
			T array = pool.remove(index);
			array.clear();
			return array;
		}
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
		
		this.poolLock.lock();
		
		try
		{
			// In James' testing pooling the checkout object wasn't necessary
			// since it is relatively small and short lived it appears
			// the GC can handle quickly discarding it.
			
			this.pooledByteArrays.addAll(checkout.getAllByteArrays());
			this.pooledShortArrays.addAll(checkout.getAllShortArrays());
			this.pooledLongArrays.addAll(checkout.getAllLongArrays());
			
			//LOGGER.info("Returned ["+checkout.byteArrayLists.size()+"/"+this.pooledByteArrays.size()+"] bytes and ["+checkout.longArrayLists.size()+"/"+this.pooledLongArrays.size()+"] longs.");\
		}
		finally
		{
			this.poolLock.unlock();
		}
	}

	
	
	//===============//
	// debug methods //
	//===============//
	
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		// total (all time created) count
		String byteArrayTotalCount = F3Screen.NUMBER_FORMAT.format(this.totalByteArrayCountRef.get());
		String shortArrayTotalCount = F3Screen.NUMBER_FORMAT.format(this.totalShortArrayCountRef.get());
		String longArrayTotalCount = F3Screen.NUMBER_FORMAT.format(this.totalLongArrayCountRef.get());
		
		// inactive items in pool
		String bytePoolCount = F3Screen.NUMBER_FORMAT.format(this.pooledByteArrays.size());
		String shortPoolCount = F3Screen.NUMBER_FORMAT.format(this.pooledShortArrays.size());
		String longPoolCount = F3Screen.NUMBER_FORMAT.format(this.pooledLongArrays.size());
		
		// pool byte size
		String bytePoolSizeInBytes = (this.lastBytePoolSizeInBytes != -1) 
				? " ~" + StringUtil.convertBytesToHumanReadable(this.lastBytePoolSizeInBytes)
				: "";
		String shortPoolSizeInBytes = (this.lastShortPoolSizeInBytes != -1)
				? " ~" + StringUtil.convertBytesToHumanReadable(this.lastShortPoolSizeInBytes)
				: "";
		String longPoolSizeInBytes = (this.lastLongPoolSizeInBytes != -1)
				? " ~" + StringUtil.convertBytesToHumanReadable(this.lastLongPoolSizeInBytes)
				: "";
		
		messageList.add("Pools:");
		messageList.add("byte[]: "+bytePoolCount+"/"+byteArrayTotalCount + bytePoolSizeInBytes);
		messageList.add("short[]: "+shortPoolCount+"/"+shortArrayTotalCount + shortPoolSizeInBytes);
		messageList.add("long[]: "+longPoolCount+"/"+longArrayTotalCount + longPoolSizeInBytes);
	}
	
	/**
	 *  shouldn't be called on the render thread as it can
	 *  take 10's of milliseconds to complete.
	 */
	public void recalculateSizeForDebugging()
	{
		this.poolLock.lock();
		try
		{
			// byte
			long bytePoolByteSize = estimateMemoryUsage(this.pooledByteArrays, Byte.BYTES);
			this.lastBytePoolSizeInBytes = Math.max(bytePoolByteSize, this.lastBytePoolSizeInBytes);
			
			// short
			long shortPoolByteSize = estimateMemoryUsage(this.pooledShortArrays, Short.BYTES);
			this.lastShortPoolSizeInBytes = Math.max(shortPoolByteSize, this.lastShortPoolSizeInBytes);
			
			// long
			long longPoolByteSize = estimateMemoryUsage(this.pooledLongArrays, Long.BYTES);
			this.lastLongPoolSizeInBytes = Math.max(longPoolByteSize, this.lastLongPoolSizeInBytes);
		}
		finally
		{
			this.poolLock.unlock();
		}
	}
	
	private static <T extends Collection<?>> long estimateMemoryUsage(ArrayList<T> pool, long elementSizeInBytes)
	{
		long longByteSize = 0;
		for (int i = 0; i < pool.size(); i++)
		{
			// Object overhead + capacity of underlying array * size of Long (8 bytes)
			long overhead = Byte.SIZE * 4;
			T array = pool.get(i);
			
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
			
			long arraySize = elementCount * elementSizeInBytes;
			longByteSize += overhead + arraySize;
		}
		return longByteSize;
	}
	
	
	
}
