package com.seibel.distanthorizons.core.generation.tasks;

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * @author Leetom
 * @version 2022-11-25
 */
public interface IWorldGenTaskTracker
{
	/** Returns true if the task hasn't been garbage collected. */
	boolean isMemoryAddressValid();
	
	@Nullable
	Consumer<ChunkSizedFullDataAccessor> getChunkDataConsumer();
	
}
