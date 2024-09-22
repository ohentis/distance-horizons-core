/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2023 James Seibel
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

package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerStateManager;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.render.renderer.generic.GenericObjectRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;

import java.util.List;

public class DhServerLevel extends AbstractDhServerLevel
{
	//=============//
	// constructor //
	//=============//
	
	public DhServerLevel(AbstractSaveStructure saveStructure, IServerLevelWrapper serverLevelWrapper, ServerPlayerStateManager serverPlayerStateManager)
	{
		super(saveStructure, serverLevelWrapper, serverPlayerStateManager);
	}
	
	
	
	//=======//
	// ticks //
	//=======//
	
	@Override
	public boolean shouldDoWorldGen()
	{
		return true; //todo;
	}
	
	
	
	//=========//
	// getters //
	//=========//
	
	@Override
	public GenericObjectRenderer getGenericRenderer() 
	{ 
		// server-only levels don't support rendering
		return null; 
	}
	@Override
	public RenderBufferHandler getRenderBufferHandler()
	{ 
		// server-only levels don't support rendering
		return null; 
	}
	
	
	
	//===========//
	// debugging //
	//===========//
	
	@Override
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		messageList.add("[${this.serverLevelWrapper.getDimensionName()}]");
	}
	
	
	
	//==========//
	// shutdown //
	//==========//
	
	@Override
	public void close()
	{
		super.close();
		this.serverside.close();
		LOGGER.info("Closed DHLevel for [${this.getLevelWrapper()}].");
	}
	
}
