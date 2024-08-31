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

package com.seibel.distanthorizons.core.logging.f3;

import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.render.renderer.generic.GenericObjectRenderer;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.coreapi.ModInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;

public class F3Screen
{
	private static final Logger LOGGER = LogManager.getLogger();
	
	public static final NumberFormat NUMBER_FORMAT = NumberFormat.getIntegerInstance();
	
	
	
	//=================//
	// injection point //
	//=================//
	
	/**
	 * F3 menu example: <br>
	 <code>
	 Distant Horizons v: 2.1.1-a-dev <br><br>
	 
	 Queued chunk updates: 0 / 1000 <br>
	 World Gen Tasks: 40/5304, (in progress: 7) <br><br>
	 
	 File thread pool tasks: 0 (complete: 759) <br>
	 Update thread pool tasks: 10 (complete: 24) <br>
	 Level Unsaved #: 0 <br>
	 File Handler Unsaved #: 0 <br>
	 Parent Update #: 12 <br><br>
	 
	 Client_Server World with 3 levels <br>
	 [overworld] rendering: Active <br>
	 [the_end] rendering: Inactive <br>
	 [the_nether] rendering: Inactive <br><br>
	 
	 VBO Render Count: 199/374 <br>
	 </code>
	 */
	public static void addStringToDisplay(List<String> messageList)
	{
		ThreadPoolExecutor worldGenPool = ThreadPoolUtil.getWorldGenExecutor();
		ThreadPoolExecutor fileHandlerPool = ThreadPoolUtil.getFileHandlerExecutor();
		ThreadPoolExecutor updatePool = ThreadPoolUtil.getUpdatePropagatorExecutor();
		ThreadPoolExecutor lodBuilderPool = ThreadPoolUtil.getChunkToLodBuilderExecutor();
		ThreadPoolExecutor bufferBuilderPool = ThreadPoolUtil.getBufferBuilderExecutor();
		ThreadPoolExecutor bufferUploaderPool = ThreadPoolUtil.getBufferUploaderExecutor();
		
		AbstractDhWorld world = SharedApi.getAbstractDhWorld();
		Iterable<? extends IDhLevel> levelIterator = world.getAllLoadedLevels();
		
		
		messageList.add("");
		messageList.add(ModInfo.READABLE_NAME+": "+ModInfo.VERSION);
		messageList.add("");
		// thread pools
		messageList.add(getThreadPoolStatString("World Gen", worldGenPool));//"World Gen Tasks: 40/5304, (in progress: 7)");
		messageList.add(getThreadPoolStatString("File Handler", fileHandlerPool));
		messageList.add(getThreadPoolStatString("Update Propagator", updatePool));
		messageList.add(getThreadPoolStatString("LOD Builder", lodBuilderPool));
		messageList.add(getThreadPoolStatString("Buffer Builder", bufferBuilderPool));
		messageList.add(getThreadPoolStatString("Buffer Uploader", bufferUploaderPool));
		messageList.add("");
		// chunk updates
		messageList.add(SharedApi.INSTANCE.getDebugMenuString());
		messageList.add("");
		// world / levels
		messageList.add(world.GetDebugMenuString());
		for (IDhLevel level : levelIterator)
		{
			level.addDebugMenuStringsToList(messageList);
			// LOD rendering
			RenderBufferHandler renderBufferHandler = level.getRenderBufferHandler();
			if (renderBufferHandler != null)
			{
				messageList.add(renderBufferHandler.getVboRenderDebugMenuString());
				String showPassString = renderBufferHandler.getShadowPassRenderDebugMenuString();
				if (showPassString != null)
				{
					messageList.add(showPassString);
				}
			}
			// Generic rendering
			GenericObjectRenderer genericRenderer = level.getGenericRenderer();
			if (genericRenderer != null)
			{
				messageList.add(genericRenderer.getVboRenderDebugMenuString());
			}
		}
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	private static String getThreadPoolStatString(String name, ThreadPoolExecutor pool)
	{
		String queueSize = (pool != null) ? NUMBER_FORMAT.format(pool.getQueue().size()) : "-";
		String completedCount = (pool != null) ? NUMBER_FORMAT.format(pool.getCompletedTaskCount()) : "-";
		
		return name+", tasks: "+queueSize+", complete: "+completedCount;
	}
	
	
	
}
