package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.file.metaData.BaseMetaData;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;

public interface IFullDataSourceProvider extends AutoCloseable
{
	void addScannedFile(Collection<File> detectedFiles);
	
	CompletableFuture<IFullDataSource> read(DhSectionPos pos);
	void write(DhSectionPos sectionPos, ChunkSizedFullDataAccessor chunkData);
	CompletableFuture<Void> flushAndSave();
	CompletableFuture<Void> flushAndSave(DhSectionPos sectionPos);
	
	void addOnUpdatedListener(Consumer<IFullDataSource> listener);
	
	//long getCacheVersion(DhSectionPos sectionPos);
	//boolean isCacheVersionValid(DhSectionPos sectionPos, long cacheVersion);
	
	CompletableFuture<IFullDataSource> onCreateDataFile(FullDataMetaFile file);
	CompletableFuture<IFullDataSource> onDataFileUpdate(IFullDataSource source, FullDataMetaFile file, Consumer<IFullDataSource> onUpdated, Function<IFullDataSource, Boolean> updater);
	File computeDataFilePath(DhSectionPos pos);
	ExecutorService getIOExecutor();

	@Nullable
    FullDataMetaFile getFileIfExist(DhSectionPos pos);
}
