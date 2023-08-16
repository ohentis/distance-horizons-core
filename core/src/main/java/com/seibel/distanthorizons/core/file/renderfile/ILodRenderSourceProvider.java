package com.seibel.distanthorizons.core.file.renderfile;

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;

import java.io.File;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * This represents LOD data that is stored in long term storage (IE LOD files stored on the hard drive) <br>
 * Example: {@link RenderSourceFileHandler RenderSourceFileHandler} <br><br>
 *
 * This is used to create {@link ColumnRenderSource}'s
 */
public interface ILodRenderSourceProvider extends AutoCloseable
{
	CompletableFuture<ColumnRenderSource> readAsync(DhSectionPos pos);
	void addScannedFile(Collection<File> detectedFiles);
	void writeChunkDataToFile(DhSectionPos sectionPos, ChunkSizedFullDataAccessor chunkData);
	CompletableFuture<Void> flushAndSaveAsync();
	
	/** Returns true if the data was refreshed, false otherwise */
	//boolean refreshRenderSource(ColumnRenderSource source);
	
	/** Deletes any data stored in the render cache so it can be re-created */
	void deleteRenderCache();
	
}
