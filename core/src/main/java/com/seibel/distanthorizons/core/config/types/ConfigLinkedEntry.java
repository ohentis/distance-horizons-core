package com.seibel.distanthorizons.core.config.types;

import com.seibel.distanthorizons.core.config.types.enums.EConfigEntryAppearance;

/**
 * Creates a UI element that copies everything from another element.
 * This only effects the UI
 *
 * @author coolGi
 */
public class ConfigLinkedEntry extends AbstractConfigType<AbstractConfigType<?, ?>, ConfigLinkedEntry>
{
	public ConfigLinkedEntry(AbstractConfigType<?, ?> value)
	{
		super(EConfigEntryAppearance.ONLY_IN_GUI, value);
	}
	
	/** Appearance shouldn't be changed */
	@Override
	public void setAppearance(EConfigEntryAppearance newAppearance) { }
	
	/** Value shouldn't be changed after creation */
	@Override
	public void set(AbstractConfigType<?, ?> newValue) { }
	
	
	public static class Builder extends AbstractConfigType.Builder<AbstractConfigType<?, ?>, Builder>
	{
		/** Appearance shouldn't be changed */
		@Override
		public Builder setAppearance(EConfigEntryAppearance newAppearance)
		{
			return this;
		}
		
		public ConfigLinkedEntry build()
		{
			return new ConfigLinkedEntry(this.tmpValue);
		}
		
	}
	
}
