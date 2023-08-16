package com.seibel.distanthorizons.core.generation.tasks;

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.pos.DhLodPos;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.function.Consumer;

/**
 * @author Leetom
 * @version 2022-11-25
 */
public final class WorldGenTaskGroup
{
	public final DhLodPos pos;
	public byte dataDetail;
	/** Only accessed by the generator polling thread */
	public final LinkedList<WorldGenTask> worldGenTasks = new LinkedList<>();
	
	
	
	public WorldGenTaskGroup(DhLodPos pos, byte dataDetail)
	{
		this.pos = pos;
		this.dataDetail = dataDetail;
	}
	
	public void consumeChunkData(ChunkSizedFullDataAccessor chunkSizedFullDataView)
	{
		Iterator<WorldGenTask> tasks = this.worldGenTasks.iterator();
		while (tasks.hasNext())
		{
			WorldGenTask task = tasks.next();
			Consumer<ChunkSizedFullDataAccessor> chunkDataConsumer = task.taskTracker.getChunkDataConsumer();
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
