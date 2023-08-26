package com.seibel.distanthorizons.core.config.eventHandlers;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.ELodShading;
import com.seibel.distanthorizons.api.enums.config.EMaxHorizontalResolution;
import com.seibel.distanthorizons.api.enums.config.EVerticalQuality;
import com.seibel.distanthorizons.core.config.listeners.IConfigListener;
import com.seibel.distanthorizons.core.config.Config;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Listens to the config and will automatically
 * clear the current render cache if certain settings are changed. <br> <br>
 *
 * Note: if additional settings should clear the render cache, add those to this listener, don't create a new listener
 */
public class RenderCacheConfigEventHandler implements IConfigListener
{
	public static RenderCacheConfigEventHandler INSTANCE = new RenderCacheConfigEventHandler();
	
	/** how long to wait in milliseconds before applying the config changes */
	private static final long TIMEOUT_IN_MS = 4_000L;
	private Timer cacheClearingTimer;
	
	
	/** private since we only ever need one handler at a time */
	private RenderCacheConfigEventHandler() { }
	
	
	
	@Override
	public void onConfigValueSet()
	{
		this.refreshRenderDataAfterTimeout();
	}
	
	@Override
	public void onUiModify() { /* do nothing, we only care about modified config values */ }
	
	
	/** Calling this method multiple times will reset the timer */
	private void refreshRenderDataAfterTimeout()
	{
		// stop the previous timer if one exists
		if (this.cacheClearingTimer != null)
		{
			this.cacheClearingTimer.cancel();
		}
		
		// create a new timer task
		TimerTask timerTask = new TimerTask()
		{
			public void run()
			{
				DhApi.Delayed.renderProxy.clearRenderDataCache();
			}
		};
		this.cacheClearingTimer = new Timer("RenderCacheConfig-Timeout-Timer");
		this.cacheClearingTimer.schedule(timerTask, TIMEOUT_IN_MS);
	}
	
}
