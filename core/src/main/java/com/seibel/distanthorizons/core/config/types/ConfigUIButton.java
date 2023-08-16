package com.seibel.distanthorizons.core.config.types;

import com.seibel.distanthorizons.core.config.types.enums.EConfigEntryAppearance;

public class ConfigUIButton extends AbstractConfigType<Runnable, ConfigUIButton>
{
	public ConfigUIButton(Runnable runnable)
	{
		super(EConfigEntryAppearance.ONLY_IN_GUI, runnable);
	}
	
	public void runAction()
	{
		new Thread(this.value).start();
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
