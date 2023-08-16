package com.seibel.distanthorizons.core.config.eventHandlers;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.listeners.IConfigListener;

public class UnsafeValuesConfigListener implements IConfigListener
{
	public static UnsafeValuesConfigListener INSTANCE = new UnsafeValuesConfigListener();
	
	@Override
	public void onConfigValueSet()
	{
		Config.Client.Advanced.Debugging.allowUnsafeValues.configBase.disableMinMax =
				Config.Client.Advanced.Debugging.allowUnsafeValues.get();
	}
	
	@Override
	public void onUiModify()
	{
		
	}
	
}
