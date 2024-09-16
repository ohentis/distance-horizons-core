package com.seibel.distanthorizons.core.multiplayer.client;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.generation.RemoteWorldRetrievalQueue;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;

/** 
 * This queue only handles LOD updates for
 * LODs that were changed when the player wasn't online
 * and the player already loaded the LODs once.
 * {@link RemoteWorldRetrievalQueue} is used for all other requests.
 * 
 * @see Config.Client.Advanced.Multiplayer.ServerNetworking#synchronizeOnLogin
 * @see RemoteWorldRetrievalQueue
 */
public class SyncOnLoginRequestQueue extends AbstractFullDataNetworkRequestQueue
{
	//=============//
	// constructor //
	//=============//
	
	public SyncOnLoginRequestQueue(IDhClientLevel level, ClientNetworkState networkState)
	{ super(networkState, level, true, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue); }
	
	
	
	//=========//
	// getters //
	//=========//
	
	@Override
	protected int getRequestRateLimit() { return this.networkState.sessionConfig.getSyncOnLoginRateLimit(); }
	
	@Override
	protected String getQueueName() { return "Sync On Login Queue"; }
	
	
	
	//==================//
	// request handling //
	//==================//
	
	@Override
	public boolean tick(DhBlockPos2D targetPos)
	{
		if (!this.networkState.sessionConfig.getSynchronizeOnLogin())
		{
			return false;
		}
		
		return super.tick(targetPos);
	}
	
	
	
}
