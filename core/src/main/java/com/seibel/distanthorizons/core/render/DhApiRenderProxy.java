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

package com.seibel.distanthorizons.core.render;

import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;

/**
 * Used to interact with Distant Horizons' rendering systems.
 *
 * @author James Seibel
 * @version 2023-2-8
 */
public class DhApiRenderProxy implements IDhApiRenderProxy
{
	public static DhApiRenderProxy INSTANCE = new DhApiRenderProxy();
	
	
	
	private DhApiRenderProxy() { }
	
	
	
	public DhApiResult<Boolean> clearRenderDataCache()
	{
		// make sure this is a valid time to run the method
		AbstractDhWorld world = SharedApi.getAbstractDhWorld();
		if (world == null)
		{
			return DhApiResult.createFail("No world loaded");
		}
		
		
		// clear the render caches for each level
		Iterable<? extends IDhLevel> loadedLevels = world.getAllLoadedLevels();
		for (IDhLevel level : loadedLevels)
		{
			if (level instanceof IDhClientLevel)
			{
				((IDhClientLevel) level).clearRenderCache();
			}
		}
		
		return DhApiResult.createSuccess();
	}
	
	
}
