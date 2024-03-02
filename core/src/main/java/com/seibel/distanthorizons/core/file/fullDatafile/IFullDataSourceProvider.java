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

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.NewFullDataSource;
import com.seibel.distanthorizons.core.file.ISourceProvider;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.repo.FullDataRepo;

import java.util.concurrent.CompletableFuture;

/**
 * Handles reading, writing, and updating {@link NewFullDataSource}'s. <br>
 * Should be backed by a database handled by a {@link FullDataRepo}.
 */
public interface IFullDataSourceProvider extends ISourceProvider<NewFullDataSource, IDhLevel>, AutoCloseable
{
	CompletableFuture<NewFullDataSource> getAsync(DhSectionPos pos);
	NewFullDataSource get(DhSectionPos pos);
	
	/** 
	 * If this provider has the ability to create (world gen) or get (networking)
	 * missing data sources this method will queue the given position
	 * for generation or retrieval.
	 */
	void queuePositionForGenerationOrRetrievalIfNecessary(DhSectionPos pos);
	
	CompletableFuture<Void> updateDataSourceAsync(NewFullDataSource chunkData);
	
	@Deprecated
	int getUnsavedDataSourceCount();
	
}
