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
	byte largestDataDetail();
	
	CompletableFuture<WorldGenResult> submitGenTask(DhLodPos pos, byte requiredDataDetail, IWorldGenTaskTracker tracker);
	void cancelGenTasks(Iterable<DhSectionPos> positions);
	
	void runCurrentGenTasksUntilBusy(DhBlockPos2D targetPos);
	
	int getWaitingTaskCount();
	int getInProgressTaskCount();
	
	CompletableFuture<Void> startClosing(boolean cancelCurrentGeneration, boolean alsoInterruptRunning);
	void close();
}
