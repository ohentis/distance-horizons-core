package com.seibel.distanthorizons.core.generation;

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.generation.tasks.IWorldGenTaskTracker;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenResult;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.multiplayer.ClientNetworkState;
import com.seibel.distanthorizons.core.network.ChildNetworkEventSource;
import com.seibel.distanthorizons.core.network.NetworkClient;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.messages.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import io.netty.channel.ChannelException;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class WorldRemoteGenerationQueue implements IWorldGenerationQueue
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private final ClientNetworkState networkState;
	private final IDhClientLevel level;
	
	private final ConcurrentMap<DhSectionPos, WorldGenQueueEntry> waitingTasks = new ConcurrentHashMap<>();
	private final Semaphore pendingTasksSemaphore = new Semaphore(Short.MAX_VALUE);
	private int pendingTasks() { return Short.MAX_VALUE - pendingTasksSemaphore.availablePermits(); }
	private final Set<CompletableFuture<FullDataSourceResponseMessage>> pendingRequests = ConcurrentHashMap.newKeySet();
	
	private volatile CompletableFuture<Void> generatorClosingFuture = null;
	
	private final F3Screen.NestedMessage f3Message = new F3Screen.NestedMessage(this::f3Log);
	private final AtomicInteger finishedRequests = new AtomicInteger();
	private final AtomicInteger totalRequests = new AtomicInteger();
	private final AtomicInteger failedRequests = new AtomicInteger();
	
	public WorldRemoteGenerationQueue(ClientNetworkState networkState, IDhClientLevel level)
	{
		this.networkState = networkState;
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
		while (generatorClosingFuture == null
				&& networkState.eventSource.parent.isReady()
				&& !waitingTasks.isEmpty()
				&& pendingTasks() < this.networkState.config.fullDataRequestRateLimit
				&& pendingTasksSemaphore.tryAcquire())
		{
			sendNewRequest(targetPos);
		}
	}
	
	private void sendNewRequest(DhBlockPos2D targetPos)
	{
		DhSectionPos sectionPos = Objects.requireNonNull(waitingTasks.keySet().stream().reduce(null, (a, b)
				-> a != null
				&& a.getCenter().getCenterBlockPos().distSquared(targetPos)
				< b.getCenter().getCenterBlockPos().distSquared(targetPos)
				? a : b));
		WorldGenQueueEntry entry = waitingTasks.remove(sectionPos);
		
		CompletableFuture<FullDataSourceResponseMessage> request = this.networkState.eventSource.parent.sendRequest(new FullDataSourceRequestMessage(sectionPos));
		pendingRequests.add(request);
		request.handle((response, throwable) ->
				{
					pendingRequests.remove(request);
					pendingTasksSemaphore.release();
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
						
						entry.future.complete(WorldGenResult.CreateSuccess(sectionPos));
					}
					catch (RateLimitedException e)
					{
						LOGGER.warn("Rate limited by server, re-queueing task: "+sectionPos, e);
						finishedRequests.decrementAndGet();
						waitingTasks.put(sectionPos, entry);
					}
					catch (ChannelException e)
					{
						finishedRequests.decrementAndGet();
						waitingTasks.put(sectionPos, entry);
					}
					catch (Throwable e)
					{
						LOGGER.error("Error while fetching full data source", e);
						failedRequests.incrementAndGet();
						entry.future.complete(WorldGenResult.CreateFail());
					}
					return null;
				});
	}
	
	private String[] f3Log()
	{
		ArrayList<String> lines = new ArrayList<>();
		lines.add("World Remote Generation Queue ["+level.getClientLevelWrapper().getDimensionType().getDimensionName()+"]");
		lines.add("  Requests: "+this.finishedRequests+" / "+this.totalRequests +" (failed: "+ this.failedRequests+")");
		lines.add("  Pending: "+this.pendingTasks()+" / "+this.networkState.config.fullDataRequestRateLimit);
		return lines.toArray(new String[0]);
	}
	
	@Override public CompletableFuture<Void> startClosing(boolean cancelCurrentGeneration, boolean alsoInterruptRunning)
	{
		return this.generatorClosingFuture = CompletableFuture.runAsync(() -> {
			while (!pendingTasksSemaphore.tryAcquire(Short.MAX_VALUE))
			{
				for (CompletableFuture<FullDataSourceResponseMessage> request : pendingRequests)
					request.cancel(false);
			}
			
			if (cancelCurrentGeneration)
			{
				for (WorldGenQueueEntry entry : this.waitingTasks.values())
					entry.future.cancel(alsoInterruptRunning);
			}
		});
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
