package com.seibel.distanthorizons.core.multiplayer.client;

import com.google.common.base.Stopwatch;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.logging.ConfigBasedSpamLogger;
import com.seibel.distanthorizons.core.network.exceptions.InvalidLevelException;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.exceptions.RequestRejectedException;
import com.seibel.distanthorizons.core.network.session.SessionClosedException;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.TimerUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedRateLimiter;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import org.apache.logging.log4j.LogManager;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public abstract class AbstractFullDataRequestQueue implements IDebugRenderable, AutoCloseable
{
	private static final ConfigBasedSpamLogger LOGGER = new ConfigBasedSpamLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get(), 3);
	
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	
	private static final Timer TASK_FINISH_TIMER = TimerUtil.CreateTimer("RequestTaskFinishTimer");
	
	private static final int MAX_RETRY_ATTEMPTS = 3;
	
	protected static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
	
	public final ClientNetworkState networkState;
	protected final IDhClientLevel level;
	private final boolean changedOnly;
	
	private volatile CompletableFuture<Void> closingFuture = null;
	
	protected final ConcurrentMap<Long, RequestQueueEntry> waitingTasks = new ConcurrentHashMap<>();
	private final Semaphore pendingTasksSemaphore = new Semaphore(Short.MAX_VALUE, true);
	
	private final AtomicInteger finishedRequests = new AtomicInteger();
	private final AtomicInteger failedRequests = new AtomicInteger();
	private final ConfigEntry<Boolean> showDebugWireframeConfig;
	
	private final SupplierBasedRateLimiter<Void> rateLimiter = new SupplierBasedRateLimiter<>(this::getRequestRateLimit);
	
	
	protected abstract int getRequestRateLimit();
	
	protected abstract String getQueueName();
	
	
	public AbstractFullDataRequestQueue(ClientNetworkState networkState, IDhClientLevel level, boolean changedOnly, ConfigEntry<Boolean> showDebugWireframeConfig)
	{
		this.networkState = networkState;
		this.level = level;
		this.changedOnly = changedOnly;
		this.showDebugWireframeConfig = showDebugWireframeConfig;
		DebugRenderer.register(this, this.showDebugWireframeConfig);
	}
	
	public CompletableFuture<Boolean> submitRequest(long sectionPos, Consumer<FullDataSourceV2> chunkDataConsumer)
	{
		return this.submitRequest(sectionPos, null, chunkDataConsumer);
	}
	public CompletableFuture<Boolean> submitRequest(long sectionPos, @Nullable Long clientTimestamp, Consumer<FullDataSourceV2> chunkDataConsumer)
	{
		LodUtil.assertTrue(DhSectionPos.getDetailLevel(sectionPos) == DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL, "Only highest-detail sections are allowed.");
		
		RequestQueueEntry entry = new RequestQueueEntry(chunkDataConsumer, clientTimestamp);
		entry.future.whenComplete((result, throwable) ->
		{
			this.waitingTasks.remove(sectionPos);
			
			this.finishedRequests.incrementAndGet();
			if (!result || throwable != null)
			{
				this.failedRequests.incrementAndGet();
			}
		});
		this.waitingTasks.put(sectionPos, entry);
		return entry.future;
	}
	
	protected int posDistanceSquared(DhBlockPos2D targetPos, long pos)
	{
		return (int) DhSectionPos.getCenterBlockPos(pos).distSquared(targetPos);
	}
	
	public synchronized boolean tick(DhBlockPos2D targetPos)
	{
		if (this.closingFuture != null || !this.networkState.isReady())
		{
			return false;
		}
		
		while (this.getWaitingTaskCount() > this.getInProgressTaskCount()
				&& this.getInProgressTaskCount() < this.getRequestRateLimit()
				&& this.pendingTasksSemaphore.tryAcquire())
		{
			if (!this.rateLimiter.tryAcquire())
			{
				this.pendingTasksSemaphore.release();
				break;
			}
			
			this.sendNewRequest(targetPos);
		}
		
		return true;
	}
	
	public void removeRetrievalRequestIf(DhSectionPos.ICancelablePrimitiveLongConsumer removeIf)
	{
		for (Map.Entry<Long, RequestQueueEntry> mapEntry : this.waitingTasks.entrySet())
		{
			long pos = mapEntry.getKey();
			RequestQueueEntry entry = mapEntry.getValue();
			
			if (removeIf.accept(pos))
			{
				LOGGER.debug("Removing request  " + mapEntry.getKey() + "...");
				
				entry.future.cancel(false);
				if (entry.request != null)
				{
					entry.request.cancel(false);
				}
			}
		}
	}
	
	private void sendNewRequest(DhBlockPos2D targetPos)
	{
		Map.Entry<Long, RequestQueueEntry> mapEntry = this.waitingTasks.entrySet().stream()
				.filter(task -> task.getValue().request == null)
				.min((x, y) -> this.posDistanceSquared(targetPos, x.getKey()) - this.posDistanceSquared(targetPos, y.getKey()))
				.orElse(null);
		
		if (mapEntry == null)
		{
			this.pendingTasksSemaphore.release();
			return;
		}
		
		long sectionPos = mapEntry.getKey();
		RequestQueueEntry entry = mapEntry.getValue();
		
		CompletableFuture<FullDataSourceResponseMessage> request = this.networkState.getSession().sendRequest(
				new FullDataSourceRequestMessage(this.level.getLevelWrapper(), sectionPos, entry.updateTimestamp),
				FullDataSourceResponseMessage.class
		);
		entry.request = request;
		request.handle((response, throwable) ->
		{
			this.pendingTasksSemaphore.release();
			
			try
			{
				if (throwable != null)
				{
					throw throwable;
				}
				
				if (response.payload != null)
				{
					FullDataSourceV2DTO dataSourceDto = this.networkState.decodeDataSourceAndReleaseBuffer(response.payload);
					
					ThreadPoolExecutor executor = ThreadPoolUtil.getNetworkCompressionExecutor();
					if (executor == null)
					{
						LOGGER.warn("Unable to handle FullDataPayload - getNetworkCompressionExecutor() is null");
						return null;
					}
					CompletableFuture.runAsync(() ->
					{
						try
						{
							FullDataSourceV2 fullDataSource = dataSourceDto.createPooledDataSource(this.level.getLevelWrapper());
							entry.chunkDataConsumer.accept(fullDataSource);
							FullDataSourceV2.DATA_SOURCE_POOL.returnPooledDataSource(fullDataSource);
						}
						catch (IOException | DataCorruptedException | InterruptedException e)
						{
							throw new RuntimeException(e);
						}
					}, executor);
				}
				else
				{
					LodUtil.assertTrue(this.changedOnly, "Received empty data source response for not changes-only request");
				}
			}
			catch (InvalidLevelException | RequestRejectedException ignored)
			{
				// We're too late / some cases might trigger a bunch of expected rejections
				return entry.future.complete(false);
			}
			catch (SessionClosedException | CancellationException ignored)
			{
				// Triggered when level is unloaded
				return entry.future.cancel(false);
			}
			catch (RateLimitedException e)
			{
				LOGGER.warn("Rate limited by server, re-queueing task [" + sectionPos + "]: " + e.getMessage());
				
				// Skip 1 second
				this.rateLimiter.acquireOrDrain(Integer.MAX_VALUE);
				entry.request = null;
				return null;
			}
			catch (Throwable e)
			{
				entry.retryAttempts--;
				LOGGER.error("Error while fetching full data source, attempts left: {} / {}", entry.retryAttempts, MAX_RETRY_ATTEMPTS, e);
				
				// Retry logic
				if (entry.retryAttempts > 0)
				{
					entry.request = null;
					return null;
				}
				else
				{
					return entry.future.complete(false);
				}
			}
			
			// Hack to work around a race condition
			// If you finish the request too quickly, the section will never render
			TASK_FINISH_TIMER.schedule(new TimerTask()
			{
				@Override
				public void run()
				{
					entry.future.complete(true);
				}
			}, 10000);
			return null;
		});
	}
	
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		messageList.add(this.getQueueName() + " [" + this.level.getClientLevelWrapper().getDimensionName() + "]");
		messageList.add("Requests: " + this.finishedRequests + " / " + (this.getWaitingTaskCount() + this.finishedRequests.get()) + " (failed: " + this.failedRequests + ", rate limit: " + this.getRequestRateLimit() + ")");
	}
	
	
	public int getWaitingTaskCount() { return this.waitingTasks.size(); }
	
	public int getInProgressTaskCount() { return Short.MAX_VALUE - this.pendingTasksSemaphore.availablePermits(); }
	
	
	public CompletableFuture<Void> startClosing(boolean alsoInterruptRunning)
	{
		return this.closingFuture = CompletableFuture.runAsync(() -> {
			Stopwatch stopwatch = Stopwatch.createStarted();
			
			do
			{
				for (RequestQueueEntry entry : this.waitingTasks.values())
				{
					entry.future.cancel(alsoInterruptRunning);
					if (entry.request != null && entry.request.cancel(alsoInterruptRunning))
					{
						this.pendingTasksSemaphore.release();
					}
				}
			}
			while (!this.pendingTasksSemaphore.tryAcquire(Short.MAX_VALUE) && stopwatch.elapsed(TimeUnit.SECONDS) < SHUTDOWN_TIMEOUT_SECONDS);
			
			if (stopwatch.elapsed(TimeUnit.SECONDS) >= SHUTDOWN_TIMEOUT_SECONDS)
			{
				LOGGER.warn(this.getQueueName() + " for " + this.level.getLevelWrapper() + " did not shutdown in " + SHUTDOWN_TIMEOUT_SECONDS + " seconds! Some unfinished tasks might be left hanging.");
			}
		});
	}
	
	@Override
	public void close()
	{
		DebugRenderer.unregister(this, this.showDebugWireframeConfig);
	}
	
	@Override
	public void debugRender(DebugRenderer r)
	{
		if (MC_CLIENT.getWrappedClientLevel() != this.level.getClientLevelWrapper())
		{
			return;
		}
		
		for (Map.Entry<Long, RequestQueueEntry> mapEntry : this.waitingTasks.entrySet())
		{
			r.renderBox(new DebugRenderer.Box(mapEntry.getKey(), -32f, 64f, 0.05f,
					mapEntry.getValue().request != null ? Color.red : Color.gray
			));
		}
	}
	
	protected static class RequestQueueEntry
	{
		public final CompletableFuture<Boolean> future = new CompletableFuture<>();
		public final Consumer<FullDataSourceV2> chunkDataConsumer;
		@Nullable
		public final Long updateTimestamp;
		
		@CheckForNull
		public CompletableFuture<?> request;
		public int retryAttempts = MAX_RETRY_ATTEMPTS;
		
		public RequestQueueEntry(
				Consumer<FullDataSourceV2> chunkDataConsumer,
				@Nullable Long updateTimestamp)
		{
			this.chunkDataConsumer = chunkDataConsumer;
			this.updateTimestamp = updateTimestamp;
		}
		
	}
	
}