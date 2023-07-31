/*
 *    This file is part of the Distant Horizons mod (formerly the LOD Mod),
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2022  James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.api.internal;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.world.DhClientServerWorld;
import com.seibel.distanthorizons.core.world.DhServerWorld;
import com.seibel.distanthorizons.core.world.IDhServerWorld;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import org.apache.logging.log4j.Logger;

/**
 * This holds the methods that should be called by the host mod loader (Fabric,
 * Forge, etc.). Specifically server events.
 */
public class ServerApi
{
	public static final ServerApi INSTANCE = new ServerApi();
	
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private int lastWorldGenTickDelta = 0;
	
	
	
	private ServerApi() { }
	
	
	
	// =============//
	// tick events  //
	// =============//
	
	public void serverTickEvent()
	{
		try
		{
			IDhServerWorld serverWorld = SharedApi.getIDhServerWorld();
			if (serverWorld != null)
			{
				serverWorld.serverTick();
				SharedApi.worldGenTick(serverWorld::doWorldGen);
			}
		}
		catch (Exception e)
		{
			// try catch is necessary to prevent crashing the internal server when an exception is thrown
			LOGGER.error("ServerTickEvent error: "+e.getMessage(), e);
		}
	}
	public void serverLevelTickEvent(IServerLevelWrapper level)
	{
		//TODO
	}

	public void serverLoadEvent(boolean isDedicatedEnvironment)
	{
		LOGGER.debug("Server World loading with (dedicated?:"+isDedicatedEnvironment+")");
		SharedApi.setDhWorld(isDedicatedEnvironment ? new DhServerWorld() : new DhClientServerWorld());
	}
	
	public void serverUnloadEvent()
	{
		LOGGER.debug("Server World "+SharedApi.getAbstractDhWorld()+" unloading");
		
		SharedApi.getAbstractDhWorld().close();
		SharedApi.setDhWorld(null);
	}
	
	public void serverLevelLoadEvent(IServerLevelWrapper level)
	{
		LOGGER.debug("Server Level "+level+" loading");
		
		AbstractDhWorld serverWorld = SharedApi.getAbstractDhWorld();
		if (serverWorld != null)
		{
			serverWorld.getOrLoadLevel(level);
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent.EventParam(level));
		}
	}
	public void serverLevelUnloadEvent(IServerLevelWrapper level)
	{
		LOGGER.debug("Server Level "+level+" unloading");
		
		AbstractDhWorld serverWorld = SharedApi.getAbstractDhWorld();
		if (serverWorld != null)
		{
			serverWorld.unloadLevel(level);
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent.EventParam(level));
		}
	}

	@Deprecated // TODO not implemented, remove
	public void serverSaveEvent()
	{
		LOGGER.debug("Server world "+SharedApi.getAbstractDhWorld()+" saving");
		
		AbstractDhWorld serverWorld = SharedApi.getAbstractDhWorld();
		if (serverWorld != null)
		{
			serverWorld.saveAndFlush();
		}
	}

	public void serverChunkLoadEvent(IChunkWrapper chunk, ILevelWrapper level)
	{
		// the world should always be non-null, this != null is just in case the world was removed accidentally 
		AbstractDhWorld dhWorld = SharedApi.getAbstractDhWorld();
		if (dhWorld != null)
		{
			IDhLevel dhLevel = dhWorld.getLevel(level);
			if (dhLevel != null)
			{
				dhLevel.updateChunkAsync(chunk);
			}
		}
	}
	public void serverChunkSaveEvent(IChunkWrapper chunk, ILevelWrapper level)
	{
		AbstractDhWorld dhWorld = SharedApi.getAbstractDhWorld();
		if (dhWorld != null)
		{
			IDhLevel dhLevel = SharedApi.getAbstractDhWorld().getLevel(level);
			if (dhLevel != null)
			{
				dhLevel.updateChunkAsync(chunk);
			}
		}
	}
	
	public void serverPlayerJoinEvent(IServerPlayerWrapper player)
	{
		IDhServerWorld serverWorld = SharedApi.getIDhServerWorld();
		if (serverWorld instanceof DhServerWorld) // TODO add support for DhClientServerWorld's (lan worlds) as well
		{
			LOGGER.debug("Waiting for player to connect: " + player.getUUID());
			((DhServerWorld) serverWorld).addPlayer(player);
		}
	}
	public void serverPlayerDisconnectEvent(IServerPlayerWrapper player)
	{
		IDhServerWorld serverWorld = SharedApi.getIDhServerWorld();
		if (serverWorld instanceof DhServerWorld) // TODO add support for DhClientServerWorld's (lan worlds) as well
		{
			LOGGER.debug("Removing player from connect wait list: " + player.getUUID());
			((DhServerWorld) serverWorld).removePlayer(player);
		}
	}
	
}
