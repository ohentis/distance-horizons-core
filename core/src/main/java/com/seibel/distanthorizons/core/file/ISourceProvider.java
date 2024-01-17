package com.seibel.distanthorizons.core.file;

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;

import java.util.concurrent.CompletableFuture;

public interface ISourceProvider<TDataSource extends IDataSource<TDhLevel>, TDhLevel extends IDhLevel> extends AutoCloseable
{
	CompletableFuture<TDataSource> getAsync(DhSectionPos pos);
	
	CompletableFuture<Void> updateDataSourcesWithChunkDataAsync(ChunkSizedFullDataAccessor chunkData);
	
}
