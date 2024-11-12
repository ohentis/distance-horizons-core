package com.seibel.distanthorizons.core.generation;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.generation.tasks.IWorldGenTaskTracker;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenResult;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.multiplayer.client.AbstractFullDataNetworkRequestQueue;
import com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.util.LodUtil;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.*;

public class RemoteWorldRetrievalQueue extends AbstractFullDataNetworkRequestQueue implements IFullDataSourceRetrievalQueue, IDebugRenderable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private int estimatedTotalTaskCount;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public RemoteWorldRetrievalQueue(ClientNetworkState networkState, DhClientLevel level)
	{ super(networkState, level, false, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue); }
	
	
	
	//===========================//
	// retrieval queue overrides //
	//===========================//
	
	@Override
	public void startAndSetTargetPos(DhBlockPos2D targetPos) { super.tick(targetPos); }
	
	@Override
	public byte lowestDataDetail() { return LodUtil.BLOCK_DETAIL_LEVEL; }
	@Override
	public byte highestDataDetail() { return LodUtil.BLOCK_DETAIL_LEVEL; }
	
	@Override
	public CompletableFuture<WorldGenResult> submitRetrievalTask(long sectionPos, byte requiredDataDetail, IWorldGenTaskTracker tracker)
	{
		return super.submitRequest(sectionPos, tracker.getDataSourceConsumer())
				.thenApply(retrievalSuccess -> retrievalSuccess
						? WorldGenResult.CreateSuccess(sectionPos)
						: WorldGenResult.CreateFail());
	}
	
	@Override
	public CompletableFuture<Void> startClosingAsync(boolean cancelCurrentGeneration, boolean alsoInterruptRunning)
	{ return super.startClosingAsync(alsoInterruptRunning); }
	
	
	
	//=================================//
	// network request queue overrides //
	//=================================//
	
	@Override
	protected int getRequestRateLimit() { return this.networkState.sessionConfig.getGenerationRequestRateLimit(); }
	@Override
	protected int getMaxRequestDistance() { return this.networkState.sessionConfig.getMaxGenerationRequestDistance(); }
	
	@Override
	protected String getQueueName() { return "World Remote Generation Queue"; }
	
	
	
	//===============//
	// debug display //
	//===============//
	
	@Override
	public int getEstimatedTotalTaskCount() { return this.estimatedTotalTaskCount; }
	@Override
	public void setEstimatedTotalTaskCount(int newEstimate) { this.estimatedTotalTaskCount = newEstimate; }
	
	
	
}