package com.seibel.distanthorizons.core.generation;

import com.seibel.distanthorizons.core.generation.tasks.IWorldGenTaskTracker;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenResult;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;

public interface IWorldGenerationQueue extends Closeable
{
	/** the largest numerical detail level */
	byte largestDataDetail();
	
	CompletableFuture<WorldGenResult> submitGenTask(DhSectionPos pos, byte requiredDataDetail, IWorldGenTaskTracker tracker);
	void cancelGenTasks(Iterable<DhSectionPos> positions);
	
	/** @param targetPos the position that world generation should be centered around, generally this will be the player's position. */
	void runCurrentGenTasksUntilBusy(DhBlockPos2D targetPos);
	
	int getWaitingTaskCount();
	int getInProgressTaskCount();
	
	CompletableFuture<Void> startClosing(boolean cancelCurrentGeneration, boolean alsoInterruptRunning);
	void close();
	
}
