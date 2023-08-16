package com.seibel.distanthorizons.core.config.types;

import com.seibel.distanthorizons.core.config.ConfigBase;
import com.seibel.distanthorizons.core.config.types.enums.EConfigEntryAppearance;

/**
 * The class where all config options should extend
 *
 * @author coolGi
 */
public abstract class AbstractConfigType<T, S>
{ // The S is the class that is extending this
	public String category = "";    // This should only be set once in the init
	public String name;            // This should only be set once in the init
	protected T value;
	public ConfigBase configBase;
	
	public Object guiValue; // This is a storage variable something like the gui can use
	
	protected EConfigEntryAppearance appearance;
	
	protected AbstractConfigType(EConfigEntryAppearance appearance, T value)
	{
		this.appearance = appearance;
		this.value = value;
	}
	
	
	/** Gets the value */
	public T get()
	{
		return this.value;
	}
	/** Sets the value */
	public void set(T newValue)
	{
		this.value = newValue;
	}
	
	public EConfigEntryAppearance getAppearance()
	{
		return appearance;
	}
	public void setAppearance(EConfigEntryAppearance newAppearance)
	{
		this.appearance = newAppearance;
	}
	
	
	public String getCategory()
	{
		return this.category;
	}
	public String getName()
	{
		return this.name;
	}
	public String getNameWCategory()
	{
		return (this.category.isEmpty() ? "" : this.category + ".") + this.name;
	}
	
	
	// Gets the class of T
	public Class<?> getType()
	{
		return value.getClass();
	}
	
	protected static abstract class Builder<T, S>
	{
		protected EConfigEntryAppearance tmpAppearance = EConfigEntryAppearance.ALL;
		protected T tmpValue;
		
		
		// Put this into your own builder
		public S setAppearance(EConfigEntryAppearance newAppearance)
		{
			this.tmpAppearance = newAppearance;
			return (S) this;
		}
		public S set(T newValue)
		{
			this.tmpValue = newValue;
			return (S) this;
		}
		
	}
	
}
