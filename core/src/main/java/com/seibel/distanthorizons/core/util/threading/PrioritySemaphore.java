package com.seibel.distanthorizons.core.util.threading;

import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * For use with {@link RateLimitedThreadPoolExecutor}
 */
public class PrioritySemaphore
{
	public int maxPermitCount;
	public int currentPermitCount;
	
	private final PriorityBlockingQueue<ThreadWithPriority> queue = new PriorityBlockingQueue<>();
	private final ReentrantLock lock = new ReentrantLock();
	
	private final Random random = new Random();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public PrioritySemaphore(int permits)
	{
		this.maxPermitCount = permits;
		this.currentPermitCount = this.maxPermitCount;
	}
	
	
	
	//==================//
	// permit acquiring //
	//==================//
	
	/** Similar to {@link Semaphore#acquire()} */
	public void acquire(RateLimitedThreadPoolExecutor executor) throws InterruptedException
	{
		Thread thread = Thread.currentThread();
		this.lock.lock();
		try
		{
			if (this.currentPermitCount > 0)
			{
				// a permit is available,
				// this thread can run normally
				this.currentPermitCount--;
				
				// return to prevent running the thread's wait() method below
				return;
			}
		}
		finally
		{
			this.lock.unlock();
		}
		
		
		// no permit is available
		// this has to be outside the try-finally to prevent holding the lock while waiting
		synchronized (thread)
		{
			// random value between -5 and +5 is used to prevent task starvation
			// while still allowing higher priority tasks to run sooner
			int priority = executor.priority + this.random.nextInt(11) - 5;
			
			// this thread will be run when a permit is available
			this.queue.put(new ThreadWithPriority(thread,priority));
			
			thread.wait();
		}
	}
	
	/** Similar to {@link Semaphore#release()} */
	public void release()
	{
		this.lock.lock();
		try
		{
			// wake up the nex thread if one is queued
			if (!this.queue.isEmpty())
			{
				Thread nextThread = this.queue.poll().thread;
				synchronized (nextThread)
				{
					nextThread.notify();
				}
			}
			else
			{
				this.currentPermitCount++;
				// don't increase past the max allowed (this can happen when changing the max permit count)
				this.currentPermitCount = Math.min(this.currentPermitCount, this.maxPermitCount);
			}
		}
		finally
		{
			this.lock.unlock();
		}
	}
	
	
	
	//=================//
	// permit changing //
	//=================//
	
	public void changePermitCount(int val) 
	{
		// find the max number of permits to increase by 
		int permitCountIncrease = Math.max(0, val - this.maxPermitCount);
		
		this.lock.lock();
		try
		{
			this.currentPermitCount += permitCountIncrease;
			this.maxPermitCount = val;
		}
		finally
		{
			this.lock.unlock();
		}
	}
	
	public int availablePermits() { return this.currentPermitCount; }
	
	
	
	//================//
	// helper classes //
	//================//
	
	/** simple sortable container to track a thread and it's priority */
	private static class ThreadWithPriority implements Comparable<ThreadWithPriority>
	{
		private final Thread thread;
		private final int priority;
		
		public ThreadWithPriority(Thread thread, int priority)
		{
			this.thread = thread;
			this.priority = priority;
		}
		
		@Override
		public int compareTo(@NotNull ThreadWithPriority other)
		{
			// highest number first
			return Integer.compare(other.priority, this.priority);
		}
		
	}
	
	
}
