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
		super.tick(targetPos);
	}
	
	
	@Override
	public int getEstimatedTotalTaskCount() { return this.estimatedTotalTaskCount; }
	@Override
	public void setEstimatedTotalTaskCount(int newEstimate) { this.estimatedTotalTaskCount = newEstimate; }
	
	@Override
	public CompletableFuture<Void> startClosing(boolean cancelCurrentGeneration, boolean alsoInterruptRunning)
	{
		return super.startClosing(alsoInterruptRunning);
	}
}