package com.seibel.distanthorizons.api.interfaces.config;

/**
 * An interface for Distant Horizon's Config.
 *
 * @param <T> The data type of this config.
 * @author James Seibel
 * @version 2022-9-15
 */
public interface IDhApiConfigValue<T>
{
	
	/**
	 * Returns the active value for this config. <br>
	 * Returns the True value if either the config cannot be overridden by
	 * the API or if it hasn't been set by the API.
	 */
	public T getValue();
	/**
	 * Returns the value held by this config. <br>
	 * This is the value stored in the config file.
	 */
	public T getTrueValue();
	/**
	 * Returns the value of the config if it was set by the API.
	 * Returns null if the config wasn't set by the API.
	 */
	public T getApiValue();
	
	/**
	 * Sets the config's value. <br>
	 * If the newValue is set to null then the config
	 * will revert to using the True Value.<br>
	 * If the config cannot be set via the API this method will return false.
	 *
	 * @return true if the value was set, false otherwise.
	 */
	public boolean setValue(T newValue);
	
	/** Returns true if this config can be set via the API, false otherwise. */
	public boolean getCanBeOverrodeByApi();
	
	/** Returns the default value for this config. */
	public T getDefaultValue();
	/** Returns the max value for this config, null if there is no max. */
	public T getMaxValue();
	/** Returns the min value for this config, null if there is no min. */
	public T getMinValue();
	
}
