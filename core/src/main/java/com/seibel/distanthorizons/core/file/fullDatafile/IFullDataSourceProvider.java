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
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.file.ISourceProvider;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.FullDataRepo;

import java.util.concurrent.CompletableFuture;

/**
 * Handles reading, writing, and updating {@link IFullDataSource}'s. <br>
 * Should be backed by a database handled by a {@link FullDataRepo}.
 */
public interface IFullDataSourceProvider extends ISourceProvider<IFullDataSource, IDhLevel>, AutoCloseable
{
	CompletableFuture<IFullDataSource> getAsync(DhSectionPos pos);
	IFullDataSource get(DhSectionPos pos);
	
	CompletableFuture<Void> updateDataSourcesWithChunkDataAsync(ChunkSizedFullDataAccessor chunkData);
	
	int getUnsavedDataSourceCount();
	
}
