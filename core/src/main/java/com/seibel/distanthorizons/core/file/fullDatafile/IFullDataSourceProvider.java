/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2023 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public interface IFullDataSourceProvider extends AutoCloseable
{
	void addScannedFiles(Collection<File> detectedFiles);
	
	CompletableFuture<IFullDataSource> readAsync(DhSectionPos pos);
	void writeChunkDataToFile(DhSectionPos sectionPos, ChunkSizedFullDataAccessor chunkData);
	CompletableFuture<Void> flushAndSave();
	CompletableFuture<Void> flushAndSave(DhSectionPos sectionPos);
	
	//long getCacheVersion(DhSectionPos sectionPos);
	//boolean isCacheVersionValid(DhSectionPos sectionPos, long cacheVersion);
	
	CompletableFuture<IFullDataSource> onDataFileCreatedAsync(FullDataMetaFile file);
	default CompletableFuture<DataFileUpdateResult> onDataFileUpdateAsync(IFullDataSource fullDataSource, FullDataMetaFile file, boolean dataChanged) { return CompletableFuture.completedFuture(new DataFileUpdateResult(fullDataSource, dataChanged)); }
	File computeDataFilePath(DhSectionPos pos);
	ExecutorService getIOExecutor();

	@Nullable
    FullDataMetaFile getFileIfExist(DhSectionPos pos);
	
	
	
	//================//
	// helper classes //
	//================//
	
	/** 
	 * After a {@link FullDataMetaFile} has been updated the {@link IFullDataSourceProvider} may also need to modify it. <br>
	 * This specifically happens during world generation. 
	 */
	class DataFileUpdateResult
	{
		IFullDataSource fullDataSource;
		boolean dataSourceChanged;
		
		public DataFileUpdateResult(IFullDataSource fullDataSource, boolean dataSourceChanged)
		{
			this.fullDataSource = fullDataSource;
			this.dataSourceChanged = dataSourceChanged;
		}
	}
	
}
