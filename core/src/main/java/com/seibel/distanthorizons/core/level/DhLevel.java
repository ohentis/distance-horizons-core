package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkModifiedEvent;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.transformers.ChunkToLodBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;

import java.util.concurrent.CompletableFuture;

public abstract class DhLevel implements IDhLevel
{
	
	public final ChunkToLodBuilder chunkToLodBuilder;
	
	protected DhLevel() { this.chunkToLodBuilder = new ChunkToLodBuilder(); }
	
	public abstract void saveWrites(ChunkSizedFullDataAccessor data);
	
	
	@Override
	public int getMinY()
	{
		return 0;
	}
	
	@Override
	public void updateChunkAsync(IChunkWrapper chunk)
	{
		CompletableFuture<ChunkSizedFullDataAccessor> future = this.chunkToLodBuilder.tryGenerateData(chunk);
		if (future != null)
		{
			future.thenAccept((chunkSizedFullDataAccessor) ->
			{
				if (chunkSizedFullDataAccessor == null)
				{
					// This can happen if, among other reasons, a chunk save is superceded by a later event
					return;
				}
				
				this.saveWrites(chunkSizedFullDataAccessor);
				ApiEventInjector.INSTANCE.fireAllEvents(
						DhApiChunkModifiedEvent.class,
						new DhApiChunkModifiedEvent.EventParam(this.getLevelWrapper(), chunk.getChunkPos().x, chunk.getChunkPos().z));
			});
		}
	}
	
	@Override
	public void close() { this.chunkToLodBuilder.close(); }
	
}
