package com.seibel.distanthorizons.core.generation;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataSourceProvider;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class PregenManager
{
	protected static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private final AtomicReference<CompletableFuture<Void>> pregenFuture = new AtomicReference<>();
	
	
	public CompletableFuture<Void> startPregen(
			IServerLevelWrapper levelWrapper,
			DhBlockPos2D origin,
			int chunkRadius,
			Consumer<String> progressUpdater
	)
	{
		PregenState pregenState = new PregenState(
				(GeneratedFullDataSourceProvider) SharedApi.getIDhServerWorld().getLevel(levelWrapper).getFullDataProvider(),
				DhSectionPos.convertToDetailLevel(
						DhSectionPos.encode(LodUtil.BLOCK_DETAIL_LEVEL, origin.x, origin.z),
						DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL
				),
				(int) Math.pow(Math.ceil((double) chunkRadius / 4 * 2), 2),
				progressUpdater
		);
		
		if (!this.pregenFuture.compareAndSet(null, pregenState))
		{
			pregenState.completeExceptionally(new IllegalStateException("Pregen is already running."));
			return pregenState;
		}
		pregenState.whenComplete((result, throwable) -> {
			this.pregenFuture.set(null);
		});
		
		pregenState.fillPendingQueue();
		return pregenState;
	}
	
	public CompletableFuture<Void> getRunningPregen()
	{
		return this.pregenFuture.get();
	}
	
	
	private static class PregenState extends CompletableFuture<Void>
	{
		private final GeneratedFullDataSourceProvider fullDataSourceProvider;
		private final long originSectionPos;
		private final int sectionsToGenerate;
		private final Consumer<String> progressUpdater;
		
		private final AtomicInteger nextSectionSpiralIndex = new AtomicInteger(0);
		private final AtomicLong lastLogTime = new AtomicLong();
		
		private final Set<Long> pendingGenerations = Collections.newSetFromMap(CacheBuilder.newBuilder()
				.expireAfterWrite(2, TimeUnit.MINUTES)
				.<Long, Boolean>removalListener(removalNotification -> {
					if (removalNotification.getCause() == RemovalCause.EXPIRED)
					{
						//noinspection DataFlowIssue
						LOGGER.warn("Generation for section " + DhSectionPos.toString(removalNotification.getKey()) + " has expired!");
					}
				})
				.build().asMap());
		
		
		public PregenState(GeneratedFullDataSourceProvider fullDataSourceProvider, long originSectionPos, int sectionsToGenerate, Consumer<String> progressUpdater)
		{
			this.fullDataSourceProvider = fullDataSourceProvider;
			this.originSectionPos = originSectionPos;
			this.sectionsToGenerate = sectionsToGenerate;
			this.progressUpdater = progressUpdater;
		}
		
		
		private void fillPendingQueue()
		{
			while (!this.isDone() && this.pendingGenerations.size() < Config.Common.MultiThreading.numberOfThreads.get())
			{
				int nextSpiralIndex = this.nextSectionSpiralIndex.getAndIncrement();
				if (nextSpiralIndex > this.sectionsToGenerate)
				{
					this.complete(null);
					return;
				}
				
				long nextSectionPos = this.sectionPosOnSpiral(nextSpiralIndex);
				
				long lastLogTime = this.lastLogTime.get();
				if (Config.Server.pregenLogIntervalSeconds.get() == 0
						|| (System.currentTimeMillis() - lastLogTime > TimeUnit.SECONDS.toMillis(Config.Server.pregenLogIntervalSeconds.get()) && this.lastLogTime.compareAndSet(lastLogTime, System.currentTimeMillis()))
				)
				{
					this.progressUpdater.accept("Next section: " + DhSectionPos.toString(nextSectionPos) + ", generated: " + nextSpiralIndex + " / " + this.sectionsToGenerate);
				}
				
				this.pendingGenerations.add(nextSectionPos);
				this.fullDataSourceProvider.getAsync(nextSectionPos).thenAccept(fullDataSource -> {
					if (this.fullDataSourceProvider.isFullyGenerated(fullDataSource.columnGenerationSteps))
					{
						this.pendingGenerations.remove(fullDataSource.getPos());
						PregenState.this.fillPendingQueue();
					}
					else
					{
						this.fullDataSourceProvider.queuePositionForRetrieval(fullDataSource.getPos()).thenAccept(result -> {
							if (result.success)
							{
								this.pendingGenerations.remove(fullDataSource.getPos());
								PregenState.this.fillPendingQueue();
							}
							else
							{
								LOGGER.warn("Failed to generate section " + DhSectionPos.toString(result.pos));
							}
						});
					}
					
					fullDataSource.close();
				});
			}
		}
		
		private long sectionPosOnSpiral(int pos)
		{
			if (pos == 0)
			{
				return this.originSectionPos;
			}
			pos--;
			
			int ringNumber = 1;
			while (pos >= ringNumber * 8)
			{
				pos -= ringNumber * 8;
				ringNumber++;
			}
			
			// 0 <= pos <= (ringNumber * 8) - 1
			// ringNumber * 8 - full ring
			// ringNumber * 4 - half-ring
			// ringNumber * 2 - quarter-ring, i.e. one side of it
			// ringNumber - half of quarter-ring
			
			int x = -ringNumber + 1 + Math.min(pos % (ringNumber * 4), ringNumber * 2 - 1);
			int z = ringNumber - Math.max(0, pos % (ringNumber * 4) - ringNumber * 2 + 1);
			
			if (pos >= ringNumber * 4)
			{
				x = -x;
				z = -z;
			}
			
			x += DhSectionPos.getX(this.originSectionPos);
			z += DhSectionPos.getZ(this.originSectionPos);
			
			return DhSectionPos.encode(DhSectionPos.getDetailLevel(this.originSectionPos), x, z);
		}
		
	}
	
}
