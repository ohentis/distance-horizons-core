package com.seibel.distanthorizons.coreapi.interfaces.config;


import java.util.function.Consumer;

/**
 * Use for making the config variables
 *
 * @author coolGi
 * @version 2022-5-26
 */
public interface IConfigEntry<T>
{
	
	/** Gets the default value of the option */
	T getDefaultValue();
	
	void setApiValue(T newApiValue);
	T getApiValue();
	
	/** Returns true if this config can be set via the API. */
	boolean getAllowApiOverride();
	
	void set(T newValue);
	T get();
	T getTrueValue();
	
	/** Sets the value without saving */
	void setWithoutSaving(T newValue);
	
	/** Gets the min value */
	T getMin();
	/** Sets the min value */
	void setMin(T newMin);
	/** Gets the max value */
	T getMax();
	/** Sets the max value */
	void setMax(T newMax);
	/** Sets the min and max in 1 setter */
	void setMinMax(T newMin, T newMax);
	
	/** Gets the comment */
	String getComment();
	/** Sets the comment */
	void setComment(String newComment);
	
	/**
	 * Checks if the option is valid
	 *
	 * 0 == valid
	 * 1 == number too high
	 * -1 == number too low
	 */
	byte isValid();
	/** Checks if a value is valid */
	byte isValid(T value);
	
	/** Is the value of this equal to another */
	boolean equals(IConfigEntry<?> obj);
	
	void addValueChangeListener(Consumer<T> onValueChangeFunc);
	
}
