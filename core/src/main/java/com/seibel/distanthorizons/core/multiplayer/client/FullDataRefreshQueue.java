package com.seibel.distanthorizons.core.multiplayer.client;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;

public class FullDataRefreshQueue extends AbstractFullDataRequestQueue
{
	public FullDataRefreshQueue(IDhClientLevel level, ClientNetworkState networkState)
	{
		super(networkState, level, true, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue);
	}
	
	@Override
	protected int getRequestConcurrencyLimit() { return this.networkState.config.postRelogUpdateConcurrencyLimit; }
	
	@Override
	protected String getQueueName() { return "Data Refresh Queue"; }
	
	@Override
	public boolean tick(DhBlockPos2D targetPos)
	{
		if (!this.networkState.config.postRelogUpdateEnabled)
		{
			return false;
		}
		return super.tick(targetPos);
	}
	
}
