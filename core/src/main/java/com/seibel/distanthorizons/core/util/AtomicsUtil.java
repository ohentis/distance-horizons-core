package com.seibel.distanthorizons.core.util;

import it.unimi.dsi.fastutil.booleans.BooleanObjectImmutablePair;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Predicate;

/**
 * While java 8 does have built in atomic operations, there doesn't seem to be any Compare And Exchange operation... <br>
 * So here we implement our own.
 */
public class AtomicsUtil
{
	
	public static <T> T conditionalAndExchange(AtomicReference<T> atomic, Predicate<T> requirement, T newValue)
	{
		while (true)
		{
			T oldValue = atomic.get();
			if (!requirement.test(oldValue)) return oldValue;
			if (atomic.weakCompareAndSet(oldValue, newValue)) return oldValue;
		}
	}
	
	public static <T> BooleanObjectImmutablePair<T> conditionalAndExchangeWeak(AtomicReference<T> atomic, Predicate<T> requirement, T newValue)
	{
		T oldValue = atomic.get();
		if (requirement.test(oldValue) && atomic.weakCompareAndSet(oldValue, newValue))
		{
			return new BooleanObjectImmutablePair<>(true, oldValue);
		}
		else
		{
			return new BooleanObjectImmutablePair<>(false, oldValue);
		}
	}
	
	public static <T> T compareAndExchange(AtomicReference<T> atomic, T expected, T newValue)
	{
		while (true)
		{
			T oldValue = atomic.get();
			if (oldValue != expected) return oldValue;
			if (atomic.weakCompareAndSet(expected, newValue)) return expected;
		}
	}
	
	public static <T> BooleanObjectImmutablePair<T> compareAndExchangeWeak(AtomicReference<T> atomic, T expected, T newValue)
	{
		T oldValue = atomic.get();
		if (oldValue == expected && atomic.weakCompareAndSet(expected, newValue))
		{
			return new BooleanObjectImmutablePair<>(true, expected);
		}
		else
		{
			return new BooleanObjectImmutablePair<>(false, oldValue);
		}
	}
	
	// Additionally, we implement some helper methods for frequently used atomic operations. //
	
	// Compare with expected value and set new value if equal. Then return whatever value the atomic now contains.
	public static <T> T compareAndSetThenGet(AtomicReference<T> atomic, T expected, T newValue)
	{
		while (true)
		{
			T oldValue = atomic.get();
			if (oldValue != expected) return oldValue;
			if (atomic.weakCompareAndSet(expected, newValue)) return newValue;
		}
	}
	
	
	
	// Below is the array version of the above. //
	
	public static <T> T compareAndExchange(AtomicReferenceArray<T> array, int index, T expected, T newValue)
	{
		while (true)
		{
			T oldValue = array.get(index);
			if (oldValue != expected) return oldValue;
			if (array.weakCompareAndSet(index, expected, newValue)) return expected;
		}
	}
	
	public static <T> BooleanObjectImmutablePair<T> compareAndExchangeWeak(AtomicReferenceArray<T> array, int index, T expected, T newValue)
	{
		T oldValue = array.get(index);
		if (oldValue == expected && array.weakCompareAndSet(index, expected, newValue))
		{
			return new BooleanObjectImmutablePair<>(true, expected);
		}
		else
		{
			return new BooleanObjectImmutablePair<>(false, oldValue);
		}
	}
	
	public static <T> T compareAndSetThenGet(AtomicReferenceArray<T> array, int index, T expected, T newValue)
	{
		while (true)
		{
			T oldValue = array.get(index);
			if (oldValue != expected) return oldValue;
			if (array.weakCompareAndSet(index, expected, newValue)) return newValue;
		}
	}
	
}
