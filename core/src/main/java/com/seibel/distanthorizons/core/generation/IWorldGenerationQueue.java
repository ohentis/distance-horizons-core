package com.seibel.distanthorizons.core.generation;

import com.seibel.distanthorizons.core.generation.tasks.IWorldGenTaskTracker;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenResult;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhLodPos;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface IWorldGenerationQueue extends Closeable
{
	byte largestDataDetail();
	
	CompletableFuture<WorldGenResult> submitGenTask(DhLodPos pos, byte requiredDataDetail, IWorldGenTaskTracker tracker);
	void runCurrentGenTasksUntilBusy(DhBlockPos2D targetPos);
	
	CompletableFuture<Void> startClosing(boolean cancelCurrentGeneration, boolean alsoInterruptRunning);
	
	void close();
}
