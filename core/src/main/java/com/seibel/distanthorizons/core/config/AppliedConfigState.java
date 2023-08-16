package com.seibel.distanthorizons.core.config;

import com.seibel.distanthorizons.core.config.types.ConfigEntry;

// TODO: Make this intergrate with the config system
public class AppliedConfigState<T>
{
	final ConfigEntry<T> entry;
	T activeValue;
	
	
	
	public AppliedConfigState(ConfigEntry<T> entryToWatch)
	{
		this.entry = entryToWatch;
		this.activeValue = entryToWatch.get();
	}
	
	
	
	/** Returns true if the value was changed */
	public boolean pollNewValue()
	{
		T newValue = this.entry.get();
		if (newValue.equals(this.activeValue))
		{
			return false;
		}
		this.activeValue = newValue;
		return true;
	}
	
	public T get() { return this.activeValue; }
	
}
