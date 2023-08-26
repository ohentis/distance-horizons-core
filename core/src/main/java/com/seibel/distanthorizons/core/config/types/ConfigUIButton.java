package com.seibel.distanthorizons.core.config.types;

import com.seibel.distanthorizons.core.config.types.enums.EConfigEntryAppearance;

public class ConfigUIButton extends AbstractConfigType<Runnable, ConfigUIButton>
{
	public ConfigUIButton(Runnable runnable)
	{
		super(EConfigEntryAppearance.ONLY_IN_GUI, runnable);
	}
	
	/** Runs the action of the button. NOTE: Will run on the main thread (so can halt the main process if not offloaded to a different thread) */
	public void runAction()
	{
		this.value.run();
	}
	
	public static class Builder extends AbstractConfigType.Builder<Runnable, Builder>
	{
		/** Appearance shouldn't be changed */
		@Override
		public Builder setAppearance(EConfigEntryAppearance newAppearance)
		{
			return this;
		}
		
		public ConfigUIButton build()
		{
			return new ConfigUIButton(this.tmpValue);
		}
		
	}
	
}
