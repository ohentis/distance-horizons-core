package com.seibel.distanthorizons.core.pooling;

import com.seibel.distanthorizons.core.util.ThreadUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Any object that needs pooled arrays should extend this object.
 * This handles setting up and tracking the necessary {@link PhantomReference}'s
 * needed to make sure none of the arrays are leaked. 
 * However, if possible, the implementing object should be closed
 * instead via a try-resource block as that will reduce the number of
 * unnecessary arrays created.
 * 
 * @see PhantomArrayListCheckout
 * @see PhantomArrayListPool
 */
public abstract class PhantomArrayListParent implements AutoCloseable
{
	private static final Logger LOGGER = LogManager.getLogger();
	
	private static final ConcurrentHashMap<Reference<? extends PhantomArrayListParent>, PhantomArrayListCheckout>
			PHANTOM_REF_TO_CHECKOUT = new ConcurrentHashMap<>();
	private static final ReferenceQueue<PhantomArrayListParent> PHANTOM_REF_QUEUE = new ReferenceQueue<>();
	
	private static final int PHANTOM_REF_CHECK_TIME_IN_MS = 5 * 1000;
	private static final ThreadPoolExecutor RECYCLER_THREAD = ThreadUtil.makeSingleThreadPool("Phantom Array Recycler");
	
	
	
	private final PhantomReference<PhantomArrayListParent> phantomReference;

	/** 
	 * It's recommended to set this as null after the child's constructor 
	 * finishes to show the pooled arrays have all been accessed 
	 */
	protected PhantomArrayListCheckout pooledArraysCheckout; 
	
	
	
	//====================//
	// static constructor //
	//====================//
	
	static { RECYCLER_THREAD.execute(() -> runPhantomReferenceCleanupLoop()); }
	
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
				
				
				int returnedByteArrayCount = 0;
				int returnedShortArrayCount = 0;
				int returnedLongArrayCount = 0;
				Reference<? extends PhantomArrayListParent> phantomRef = PHANTOM_REF_QUEUE.poll();
				while (phantomRef != null)
				{
					// return the pooled arrays
					PhantomArrayListCheckout checkout = PHANTOM_REF_TO_CHECKOUT.remove(phantomRef);
					if (checkout != null)
					{
						returnedByteArrayCount += checkout.getByteArrayCount();
						returnedShortArrayCount += checkout.getShortArrayCount();
						returnedLongArrayCount += checkout.getLongArrayCount();
						PhantomArrayListPool.INSTANCE.returnCheckout(checkout);
					}
					else
					{
						// shouldn't happen, but just in case
						LOGGER.warn("Unable to find checkout for phantom reference ["+phantomRef+"], arrays will need to be recreated.");
					}
					
					phantomRef = PHANTOM_REF_QUEUE.poll();
				}
				
				if (returnedByteArrayCount != 0 && returnedLongArrayCount != 0)
				{
					// we only want to log when arrays have been returned
					//LOGGER.info("Returned byte:["+F3Screen.NUMBER_FORMAT.format(returnedByteArrayCount)+"], short:["+F3Screen.NUMBER_FORMAT.format(returnedShortArrayCount)+"], long:["+F3Screen.NUMBER_FORMAT.format(returnedLongArrayCount)+"].");
					
					// since this is just for debugging it only needs to be recalculated once in a while
					PhantomArrayListPool.INSTANCE.recalculateSizeForDebugging();
				}
			}
			catch (Exception e)
			{
				LOGGER.error("Unexpected error in phantom pool return thread, error: [" + e.getMessage() + "].", e);
			}
		}
	}
	
	
	
	//=============//
	// constructor //
	//=============//
	
	/** The Array counts can be 0 or greater. */
	public PhantomArrayListParent(int byteArrayCount, int shortArrayCount, int longArrayCount) 
	{
		if (byteArrayCount < 0 || shortArrayCount < 0 || longArrayCount < 0)
		{
			throw new IllegalArgumentException("Can't get a negative number of pooled arrays.");
		}
		
		this.phantomReference = new PhantomReference<>(this, PHANTOM_REF_QUEUE);
		this.pooledArraysCheckout = PhantomArrayListPool.INSTANCE.checkoutArrays(byteArrayCount, shortArrayCount, longArrayCount);
		PHANTOM_REF_TO_CHECKOUT.put(this.phantomReference, this.pooledArraysCheckout);
	}
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override 
	public void close() //throws Exception
	{
		try
		{
			this.phantomReference.clear();
			PhantomArrayListCheckout checkout = PHANTOM_REF_TO_CHECKOUT.remove(this.phantomReference);
			PhantomArrayListPool.INSTANCE.returnCheckout(checkout);
		}
		catch (Exception e)
		{
			LOGGER.error("", e);
		}
	}
	
	
	
}
