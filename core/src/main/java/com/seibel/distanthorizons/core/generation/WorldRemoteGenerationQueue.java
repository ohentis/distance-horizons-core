package com.seibel.distanthorizons.core.generation;

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.generation.tasks.IWorldGenTaskTracker;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenResult;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.network.NetworkClient;
import com.seibel.distanthorizons.core.network.messages.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import org.apache.logging.log4j.Logger;

import javax.annotation.CheckForNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class WorldRemoteGenerationQueue implements IWorldGenerationQueue
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private static final int MAX_CONCURRENT_REQUESTS = 5;
	
	private final NetworkClient networkClient;
	private final IDhClientLevel level;
	
	private final ConcurrentMap<DhSectionPos, WorldGenQueueEntry> waitingTasks = new ConcurrentHashMap<>();
	private final AtomicInteger pendingTasks = new AtomicInteger();
	
	private final F3Screen.NestedMessage f3Message = new F3Screen.NestedMessage(this::f3Log);
	private final AtomicInteger finishedRequests = new AtomicInteger();
	private final AtomicInteger totalRequests = new AtomicInteger();
	private final AtomicInteger failedRequests = new AtomicInteger();
	
	public WorldRemoteGenerationQueue(NetworkClient networkClient, IDhClientLevel level)
	{
		this.networkClient = networkClient;
		this.level = level;
	}
	
	@Override public byte largestDataDetail()
	{
		return LodUtil.BLOCK_DETAIL_LEVEL;
	}
	
	@Override public CompletableFuture<WorldGenResult> submitGenTask(DhLodPos lodPos, byte requiredDataDetail, IWorldGenTaskTracker tracker)
	{
		LodUtil.assertTrue(lodPos.detailLevel == DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL, "Only highest-detail sections are allowed.");
		DhSectionPos sectionPos = new DhSectionPos(lodPos.detailLevel, lodPos);
		
		totalRequests.incrementAndGet();
		
		WorldGenQueueEntry entry = new WorldGenQueueEntry(new CompletableFuture<>(), tracker);
		waitingTasks.put(sectionPos, entry);
		return entry.future;
	}
	
	@Override public void runCurrentGenTasksUntilBusy(DhBlockPos2D targetPos)
	{
		if (pendingTasks.get() > MAX_CONCURRENT_REQUESTS || waitingTasks.isEmpty())
			return;
		
		DhSectionPos sectionPos = waitingTasks.keySet().stream().reduce(null, (a, b)
				-> a != null
				&& a.getCenter().getCenterBlockPos().distSquared(targetPos)
					< b.getCenter().getCenterBlockPos().distSquared(targetPos)
				? a : b);
		
		WorldGenQueueEntry entry = waitingTasks.remove(sectionPos);
		pendingTasks.incrementAndGet();
		
		networkClient.<FullDataSourceResponseMessage>sendRequest(new FullDataSourceRequestMessage(sectionPos))
				.handle((response, throwable) -> {
					pendingTasks.decrementAndGet();
					finishedRequests.incrementAndGet();
					
					try
					{
						if (throwable != null)
							throw throwable;
						
						LOGGER.info("FullDataSourceResponseMessage " + sectionPos);
						CompleteFullDataSource fullDataSource = response.getFullDataSource(sectionPos, level);
						
						Consumer<ChunkSizedFullDataAccessor> chunkDataConsumer = entry.tracker.getChunkDataConsumer();
						
						sectionPos.forEachChildAtLevel(LodUtil.CHUNK_DETAIL_LEVEL, childPos -> {
							ChunkSizedFullDataAccessor accessor = new ChunkSizedFullDataAccessor(new DhChunkPos(childPos.sectionX, childPos.sectionZ));
							
							int detailLevelDifference = sectionPos.sectionDetailLevel - childPos.sectionDetailLevel;
							int childRelativeX = childPos.sectionX - sectionPos.sectionX * BitShiftUtil.powerOfTwo(detailLevelDifference);
							int childRelativeZ = childPos.sectionZ - sectionPos.sectionZ * BitShiftUtil.powerOfTwo(detailLevelDifference);
							
							fullDataSource.subView(
									LodUtil.CHUNK_WIDTH,
									childRelativeX * LodUtil.CHUNK_WIDTH,
									childRelativeZ * LodUtil.CHUNK_WIDTH
							).shadowCopyTo(accessor);
							
							chunkDataConsumer.accept(accessor);
						});
						
						return entry.future.complete(WorldGenResult.CreateSuccess(sectionPos));
					}
					catch (Throwable e)
					{
						failedRequests.incrementAndGet();
						LOGGER.error("Error while fetching full data source", e);
						return entry.future.complete(WorldGenResult.CreateFail());
					}
				});
	}
	
	private String[] f3Log()
	{
		ArrayList<String> lines = new ArrayList<>();
		lines.add("World Remote Generation Queue ["+level.getClientLevelWrapper().getDimensionType().getDimensionName()+"]");
		lines.add("  Requests: "+this.finishedRequests +" / "+this.totalRequests +" (failed: "+ this.failedRequests+")");
		return lines.toArray(new String[0]);
	}
	
	@Override public CompletableFuture<Void> startClosing(boolean cancelCurrentGeneration, boolean alsoInterruptRunning)
	{
		// TODO Cancel generation requests?
		return CompletableFuture.completedFuture(null);
	}
	
	@Override public void close()
	{
		f3Message.close();
	}
	
	private static class WorldGenQueueEntry
	{
		public CompletableFuture<WorldGenResult> future;
		public IWorldGenTaskTracker tracker;
		
		public WorldGenQueueEntry(CompletableFuture<WorldGenResult> future, IWorldGenTaskTracker tracker)
		{
			this.future = future;
			this.tracker = tracker;
		}
	}
}
