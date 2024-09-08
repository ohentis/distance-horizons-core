package com.seibel.distanthorizons.core.multiplayer.client;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;

public class SyncOnLoginRequestQueue extends AbstractFullDataRequestQueue
{
	public SyncOnLoginRequestQueue(IDhClientLevel level, ClientNetworkState networkState)
	{
		super(networkState, level, true, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue);
	}
	
	@Override
	protected int getRequestRateLimit() { return this.networkState.config.getSyncOnLoginRateLimit(); }
	
	@Override
	protected String getQueueName() { return "Sync On Login Queue"; }
	
	@Override
	public boolean tick(DhBlockPos2D targetPos)
	{
		if (!this.networkState.config.getSynchronizeOnLogin())
		{
			return false;
		}
		return super.tick(targetPos);
	}
	
}
