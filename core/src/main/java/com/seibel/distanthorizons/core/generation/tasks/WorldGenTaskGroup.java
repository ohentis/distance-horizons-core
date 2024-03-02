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

package com.seibel.distanthorizons.core.generation.tasks;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.NewFullDataSource;
import com.seibel.distanthorizons.core.pos.DhSectionPos;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.function.Consumer;

/**
 * @author Leetom
 * @version 2022-11-25
 */
@Deprecated // TODO look into how these are used and if they should continue to be used
public final class WorldGenTaskGroup
{
	public final DhSectionPos pos;
	public byte dataDetail;
	/** Only accessed by the generator polling thread */
	public final LinkedList<WorldGenTask> worldGenTasks = new LinkedList<>();
	
	
	
	public WorldGenTaskGroup(DhSectionPos pos, byte dataDetail)
	{
		this.pos = pos;
		this.dataDetail = dataDetail;
	}
	
	public void consumeChunkData(NewFullDataSource chunkSizedFullDataView)
	{
		Iterator<WorldGenTask> tasks = this.worldGenTasks.iterator();
		while (tasks.hasNext())
		{
			WorldGenTask task = tasks.next();
			Consumer<NewFullDataSource> chunkDataConsumer = task.taskTracker.getChunkDataConsumer();
			if (chunkDataConsumer == null)
			{
				tasks.remove();
				task.future.complete(WorldGenResult.CreateFail());
			}
			else
			{
				chunkDataConsumer.accept(chunkSizedFullDataView);
			}
		}
	}
	
}
