package com.seibel.distanthorizons.core.util.ratelimiting;

import com.google.common.util.concurrent.RateLimiter;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Limits rate of tasks based on given current limit supplier. <br>
 * If rate limit was exceeded, acquisitions will fail and the provided failure handler will be called instead.
 * @param <T> Type of the object used as context for failure handler.
 */
@SuppressWarnings("UnstableApiUsage")
public class SupplierBasedRateLimiter<T>
{
	private final Supplier<Integer> maxRateSupplier;
	private final Consumer<T> onFailureConsumer;
	
	private final RateLimiter rateLimiter = RateLimiter.create(1);
	
	public SupplierBasedRateLimiter(Supplier<Integer> maxRateSupplier, Consumer<T> onFailureConsumer)
	{
		this.maxRateSupplier = maxRateSupplier;
		this.onFailureConsumer = onFailureConsumer;
	}
	
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	public boolean tryAcquire(T context)
	{
		return tryAcquire(1, context);
	}
	
	public boolean tryAcquire(int permits, T context)
	{
		rateLimiter.setRate(maxRateSupplier.get());
		if (!rateLimiter.tryAcquire(permits))
		{
			this.onFailureConsumer.accept(context);
			return false;
		}
		
		return true;
	}
}
