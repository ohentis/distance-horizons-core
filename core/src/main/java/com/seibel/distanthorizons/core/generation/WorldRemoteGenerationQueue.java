package com.seibel.distanthorizons.core.generation;

import com.google.common.base.Stopwatch;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.generation.tasks.IWorldGenTaskTracker;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenResult;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState;
import com.seibel.distanthorizons.core.network.exceptions.InvalidLevelException;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.messages.fullData.generation.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.generation.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.generation.priority.GenTaskPriorityRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.generation.priority.GenTaskPriorityResponseMessage;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.util.LodUtil;
import io.netty.channel.ChannelException;
import org.apache.logging.log4j.Logger;

import javax.annotation.CheckForNull;
import java.awt.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class WorldRemoteGenerationQueue implements IWorldGenerationQueue, IDebugRenderable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
	
	private final ClientNetworkState networkState;
	private final IDhClientLevel level;
	
	// Used to prevent requests for section very far away, as result of request list not completely filled.
	// Kinda a hack, since queue is not notified when file handler is done with feeding sections to generate
	private static final ConfigEntry<Integer> REQUEST_BEGIN_DELAY = Config.Client.Advanced.Multiplayer.ServerNetworking.fullDataRequestBeginDelay;
	private final Stopwatch requestBeginStopwatch = Stopwatch.createStarted();
	
	private volatile CompletableFuture<Void> generatorClosingFuture = null;
	
	private final ConcurrentMap<DhSectionPos, WorldGenQueueEntry> waitingTasks = new ConcurrentHashMap<>();
	private final Semaphore pendingTasksSemaphore = new Semaphore(Short.MAX_VALUE, true);
	
	private CompletableFuture<?> genTaskPriorityRequest = CompletableFuture.completedFuture(null);
	private final Semaphore genTaskPriorityRequestSemaphore = new Semaphore(1, true);
	
	private final F3Screen.NestedMessage f3Message = new F3Screen.NestedMessage(this::f3Log);
	private final AtomicInteger finishedRequests = new AtomicInteger();
	private final AtomicInteger failedRequests = new AtomicInteger();
	
	private final Set<DhSectionPos> alreadyGeneratedPosHashSet = ConcurrentHashMap.newKeySet();
	
	public WorldRemoteGenerationQueue(ClientNetworkState networkState, IDhClientLevel level)
	{
		this.networkState = networkState;
		this.level = level;
		DebugRenderer.register(this, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue);
	}
	
	
	@Override
	public byte lowestDataDetail()
	{
		return LodUtil.BLOCK_DETAIL_LEVEL;
	}
	@Override
	public byte highestDataDetail()
	{
		return LodUtil.BLOCK_DETAIL_LEVEL;
	}
	
	@Override
	public CompletableFuture<WorldGenResult> submitGenTask(DhSectionPos sectionPos, byte requiredDataDetail, IWorldGenTaskTracker tracker)
	{
		LodUtil.assertTrue(sectionPos.getDetailLevel() == DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL, "Only highest-detail sections are allowed.");
		
		// check if this is a duplicate generation task
		if (this.alreadyGeneratedPosHashSet.contains(sectionPos))
		{
			// temporary solution to prevent generating the same section multiple times
			LOGGER.trace("Duplicate generation section " + sectionPos + ". Skipping...");
			return CompletableFuture.completedFuture(WorldGenResult.CreateFail());
		}
		this.alreadyGeneratedPosHashSet.add(sectionPos);
		
		WorldGenQueueEntry entry = new WorldGenQueueEntry(new CompletableFuture<>(), tracker);
		waitingTasks.put(sectionPos, entry);
		return entry.future;
	}
	
	private int posDistanceSquared(DhBlockPos2D targetPos, DhSectionPos pos)
	{
		return (int) pos.getCenterBlockPos().distSquared(targetPos);
	}
	
	@Override
	public void startGenerationQueueAndSetTargetPos(DhBlockPos2D targetPos)
	{
		if (generatorClosingFuture != null || !networkState.getClient().isReady()) return;
		if (requestBeginStopwatch.elapsed(TimeUnit.SECONDS) < REQUEST_BEGIN_DELAY.get()) return;
		
		while (getWaitingTaskCount() > getInProgressTaskCount()
				&& getInProgressTaskCount() < this.networkState.config.fullDataRequestConcurrencyLimit
				&& pendingTasksSemaphore.tryAcquire())
		{
			sendNewRequest(targetPos);
		}
		
		if (genTaskPriorityRequestSemaphore.tryAcquire()) {
			List<DhSectionPos> posList = waitingTasks.entrySet().stream()
					.filter(task -> task.getValue().request == null && task.getValue().priority == 0)
					.sorted((x, y) -> posDistanceSquared(targetPos, x.getKey()) - posDistanceSquared(targetPos, y.getKey()))
					.limit(this.networkState.config.genTaskPriorityRequestRateLimit)
					.map(Map.Entry::getKey)
					.collect(Collectors.toList());
			if (posList.isEmpty()) {
				genTaskPriorityRequestSemaphore.release();
				return;
			};
			
			CompletableFuture<GenTaskPriorityResponseMessage> request = this.networkState.getClient().sendRequest(new GenTaskPriorityRequestMessage(posList), GenTaskPriorityResponseMessage.class);
			genTaskPriorityRequest = request;
			request.handleAsync((response, throwable) -> {
				try
				{
					if (throwable != null)
						throw throwable;
					
					for (Map.Entry<DhSectionPos, Integer> mapEntry : response.posList.entrySet())
					{
						WorldGenQueueEntry entry = waitingTasks.get(mapEntry.getKey());
						if (entry != null)
							entry.priority = mapEntry.getValue();
					}
				}
				catch (ChannelException | CancellationException | RateLimitedException ignored)
				{
				}
				catch (Throwable e)
				{
					LOGGER.error("Error while fetching gen task priorities", e);
				}
				
				genTaskPriorityRequestSemaphore.release();
				return null;
			});
		}
	}
	
	@Override
	public void cancelGenTasks(Iterable<DhSectionPos> positions)
	{
		for (DhSectionPos pos : positions)
		{
			WorldGenQueueEntry entry = waitingTasks.remove(pos);
			if (entry != null)
			{
				entry.future.cancel(false);
				if (entry.request != null)
					entry.request.cancel(false);
				alreadyGeneratedPosHashSet.remove(pos);
			}
		}
	}
	
	private void sendNewRequest(DhBlockPos2D targetPos)
	{
		Map.Entry<DhSectionPos, WorldGenQueueEntry> mapEntry = waitingTasks.entrySet().stream()
				.filter(task -> task.getValue().request == null)
				.reduce(null, (a, b)
						-> a == null
						|| b.getValue().priority > a.getValue().priority
						|| (b.getValue().priority == a.getValue().priority && posDistanceSquared(targetPos, b.getKey()) < posDistanceSquared(targetPos, a.getKey()))
						? b : a);
		if (mapEntry == null)
		{
			pendingTasksSemaphore.release();
			return;
		}
		
		DhSectionPos sectionPos = mapEntry.getKey();
		WorldGenQueueEntry entry = mapEntry.getValue();
		
		CompletableFuture<FullDataSourceResponseMessage> request = this.networkState.getClient().sendRequest(new FullDataSourceRequestMessage(level.getLevelWrapper(), sectionPos), FullDataSourceResponseMessage.class);
		entry.request = request;
		request.handleAsync((response, throwable) ->
		{
			pendingTasksSemaphore.release();
			finishedRequests.incrementAndGet();
			
			try
			{
				if (throwable != null)
					throw throwable;
				
				waitingTasks.remove(sectionPos);
				LOGGER.debug("FullDataSourceResponseMessage " + sectionPos);
				
				CompleteFullDataSource fullDataSource = response.getFullDataSource(sectionPos, level);
				Consumer<ChunkSizedFullDataAccessor> chunkDataConsumer = entry.tracker.getChunkDataConsumer();
				
				// FIXME Why keeping a reference in first place
				if (chunkDataConsumer == null)
					return entry.future.cancel(false);
				
				fullDataSource.splitIntoChunkSizedAccessors(chunkDataConsumer);
				response.getFullDataSourceLoader().returnPooledDataSource(fullDataSource);
			}
			catch (InvalidLevelException ignored)
			{
				// We're too late
			}
			catch (ChannelException | RateLimitedException e)
			{
				if (e instanceof RateLimitedException)
					LOGGER.warn("Rate limited by server, re-queueing task [" + sectionPos + "]: " + e.getMessage());
				
				entry.request = null;
				finishedRequests.decrementAndGet();
			}
			catch (CancellationException ignored)
			{
				finishedRequests.decrementAndGet();
			}
			catch (Throwable e)
			{
				LOGGER.error("Error while fetching full data source", e);
				failedRequests.incrementAndGet();
				return entry.future.complete(WorldGenResult.CreateFail());
			}
			
			return entry.future.complete(WorldGenResult.CreateSuccess(sectionPos));
		});
	}
	
	private String[] f3Log()
	{
		ArrayList<String> lines = new ArrayList<>();
		lines.add("World Remote Generation Queue ["+level.getClientLevelWrapper().getDimensionType().getDimensionName()+"]");
		lines.add("Requests: "+this.finishedRequests+" / "+(this.getWaitingTaskCount() + this.finishedRequests.get())+" (failed: "+ this.failedRequests+", rate limit: "+this.networkState.config.fullDataRequestConcurrencyLimit +")");
		return lines.toArray(new String[0]);
	}
	
	@Override
	public int getWaitingTaskCount() { return this.waitingTasks.size(); }
	@Override
	public int getInProgressTaskCount() { return Short.MAX_VALUE - pendingTasksSemaphore.availablePermits(); }
	
	@Override
	public CompletableFuture<Void> startClosing(boolean cancelCurrentGeneration, boolean alsoInterruptRunning)
	{
		return this.generatorClosingFuture = CompletableFuture.runAsync(() -> {
			Stopwatch stopwatch = Stopwatch.createStarted();
			
			do
			{
				if (genTaskPriorityRequest.cancel(false))
					genTaskPriorityRequestSemaphore.release();
			}
			while (!genTaskPriorityRequestSemaphore.tryAcquire() && stopwatch.elapsed(TimeUnit.SECONDS) < SHUTDOWN_TIMEOUT_SECONDS);
				
			do
			{
				for (WorldGenQueueEntry entry : this.waitingTasks.values())
				{
					entry.future.cancel(alsoInterruptRunning);
					if (entry.request != null && entry.request.cancel(alsoInterruptRunning))
						pendingTasksSemaphore.release();
				}
			}
			while (!pendingTasksSemaphore.tryAcquire(Short.MAX_VALUE) && stopwatch.elapsed(TimeUnit.SECONDS) < SHUTDOWN_TIMEOUT_SECONDS);
		
			if (stopwatch.elapsed(TimeUnit.SECONDS) >= SHUTDOWN_TIMEOUT_SECONDS)
				LOGGER.warn("Generation queue for " + level.getLevelWrapper() + " did not shutdown in " + SHUTDOWN_TIMEOUT_SECONDS + " seconds! Some unfinished tasks might be left hanging.");
		});
	}
	
	@Override
	public void close()
	{
		f3Message.close();
		DebugRenderer.unregister(this, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue);
	}
	
	@Override
	public void debugRender(DebugRenderer r) 
	{
		for (Map.Entry<DhSectionPos, WorldGenQueueEntry> mapEntry : waitingTasks.entrySet())
		{
			r.renderBox(new DebugRenderer.Box(mapEntry.getKey(), -32f, 64f, 0.05f,
					mapEntry.getValue().request != null ? Color.red
							: mapEntry.getValue().priority == 3 ? Color.orange
							: mapEntry.getValue().priority == 2 ? Color.cyan
							: mapEntry.getValue().priority == 1 ? Color.blue
							: Color.gray
			));
		}
	}
	
	private static class WorldGenQueueEntry
	{
		public final CompletableFuture<WorldGenResult> future;
		public final IWorldGenTaskTracker tracker;
		
		// Higher value = higher priority.
		// Priority of 0 is reserved for unassigned value
		public int priority = 0;
		@CheckForNull
		public CompletableFuture<?> request;
		
		public WorldGenQueueEntry(CompletableFuture<WorldGenResult> future, IWorldGenTaskTracker tracker)
		{
			this.future = future;
			this.tracker = tracker;
		}
	}
}
