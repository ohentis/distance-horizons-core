package com.seibel.distanthorizons.core.generation;

import com.google.common.base.Stopwatch;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.generation.tasks.IWorldGenTaskTracker;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenResult;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.multiplayer.client.AbstractFullDataRequestQueue;
import com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.messages.netty.fullData.generation.GenTaskPriorityRequestMessage;
import com.seibel.distanthorizons.core.network.messages.netty.fullData.generation.GenTaskPriorityResponseMessage;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.util.LodUtil;
import io.netty.channel.ChannelException;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class WorldRemoteGenerationQueue extends AbstractFullDataRequestQueue implements IFullDataSourceRetrievalQueue, IDebugRenderable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private int estimatedTotalTaskCount;
	
	private CompletableFuture<?> genTaskPriorityRequest = CompletableFuture.completedFuture(null);
	private final Semaphore genTaskPriorityRequestSemaphore = new Semaphore(1, true);
	
	
	@Override
	protected int getRequestConcurrencyLimit() { return this.networkState.config.fullDataRequestConcurrencyLimit; }
	
	@Override
	protected String getQueueName() { return "World Remote Generation Queue"; }
	
	
	public WorldRemoteGenerationQueue(ClientNetworkState networkState, IDhClientLevel level)
	{
		super(networkState, level, false, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue);
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
	protected double getPriorityDistanceRatio()
	{
		return Config.Client.Advanced.Multiplayer.ServerNetworking.genTaskPriorityDistanceRatio.get();
	}
	
	@Override
	public CompletableFuture<WorldGenResult> submitGenTask(DhSectionPos sectionPos, byte requiredDataDetail, IWorldGenTaskTracker tracker)
	{
		return super.submitRequest(sectionPos, tracker.getChunkDataConsumer())
				.thenApply(result -> result
						? WorldGenResult.CreateSuccess(sectionPos)
						: WorldGenResult.CreateFail());
	}
	
	@Override
	public void startAndSetTargetPos(DhBlockPos2D targetPos)
	{
		if (!super.tick(targetPos))
		{
			return;
		}
		
		if (this.genTaskPriorityRequestSemaphore.tryAcquire()) {
			List<DhSectionPos> posList = this.waitingTasks.entrySet().stream()
					.filter(task -> task.getValue().request == null && task.getValue().priority == 0)
					.sorted((x, y) -> this.posDistanceSquared(targetPos, x.getKey()) - this.posDistanceSquared(targetPos, y.getKey()))
					.limit(this.networkState.config.genTaskPriorityRequestRateLimit)
					.map(Map.Entry::getKey)
					.collect(Collectors.toList());
			if (posList.isEmpty()) {
				this.genTaskPriorityRequestSemaphore.release();
				return;
			};
			
			CompletableFuture<GenTaskPriorityResponseMessage> request = this.networkState.getClient().sendRequest(new GenTaskPriorityRequestMessage(posList, this.level), GenTaskPriorityResponseMessage.class);
			this.genTaskPriorityRequest = request;
			request.handleAsync((response, throwable) -> {
				try
				{
					if (throwable != null)
					{
						throw throwable;
					}
					
					for (Map.Entry<DhSectionPos, Integer> mapEntry : response.posList.entrySet())
					{
						RequestQueueEntry entry = this.waitingTasks.get(mapEntry.getKey());
						if (entry != null)
						{
							entry.priority = mapEntry.getValue();
						}
					}
				}
				catch (ChannelException | CancellationException | RateLimitedException ignored)
				{
				}
				catch (Throwable e)
				{
					LOGGER.error("Error while fetching gen task priorities", e);
				}
				
				this.genTaskPriorityRequestSemaphore.release();
				return null;
			});
		}
	}
	
	
	@Override
	public int getEstimatedTotalTaskCount() { return this.estimatedTotalTaskCount; }
	@Override
	public void setEstimatedTotalTaskCount(int newEstimate) { this.estimatedTotalTaskCount = newEstimate; }
	
	@Override
	public CompletableFuture<Void> startClosing(boolean cancelCurrentGeneration, boolean alsoInterruptRunning)
	{
		return CompletableFuture.allOf(super.startClosing(alsoInterruptRunning), CompletableFuture.runAsync(() -> {
			Stopwatch stopwatch = Stopwatch.createStarted();
			
			do
			{
				if (this.genTaskPriorityRequest.cancel(false))
				{
					this.genTaskPriorityRequestSemaphore.release();
				}
			}
			while (!this.genTaskPriorityRequestSemaphore.tryAcquire() && stopwatch.elapsed(TimeUnit.SECONDS) < SHUTDOWN_TIMEOUT_SECONDS);
			
			if (stopwatch.elapsed(TimeUnit.SECONDS) >= SHUTDOWN_TIMEOUT_SECONDS)
			{
				LOGGER.warn("Priority request queue for " + this.level.getLevelWrapper() + " did not shutdown in " + SHUTDOWN_TIMEOUT_SECONDS + " seconds! It might be left hanging.");
			}
		}));
	}
}