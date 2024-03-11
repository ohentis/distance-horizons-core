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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/**
 * Handles reading, writing, and updating {@link NewFullDataSource}'s. <br>
 * Should be backed by a database handled by a {@link FullDataRepo}.
 */
public interface IFullDataSourceProvider extends ISourceProvider<NewFullDataSource, IDhLevel>, AutoCloseable
{
	CompletableFuture<NewFullDataSource> getAsync(DhSectionPos pos);
	NewFullDataSource get(DhSectionPos pos);
	
	CompletableFuture<Void> updateDataSourceAsync(NewFullDataSource chunkData);
	
	/** @return -1 if this provider never has unsaved data sources */
	default int getUnsavedDataSourceCount() { return -1; }
	
	
	
	// retrieval (world gen) //
	
	/**
	 * If true this {@link IFullDataSourceProvider} can generate or retrieve
	 * {@link NewFullDataSource}'s that aren't currently in the database.
	 */
	default boolean canRetrieveMissingDataSources() { return false; }
	
	/** @return null if it was unable to generate any positions, an empty array if all positions were generated */
	@Nullable
	default ArrayList<DhSectionPos> getPositionsToRetrieve(DhSectionPos pos)  { return null; }
	/**
	 * Returns how many positions could potentially be generated for this position assuming the position is empty.
	 * Used when estimating the total number of retrieval requests.
	 */
	default int getMaxPossibleRetrievalPositionCountForPos(DhSectionPos pos)  { return -1; }
	
	/** @return true if the position was queued, false if not */
	default boolean queuePositionForRetrieval(DhSectionPos genPos) { return false; }
	/** 
	 * @return false if the provider isn't accepting new requests,
	 *          this can be due to having a full queue or some other
	 *          limiting factor.
	 */
	default boolean canQueueRetrieval() { return false; }
	
	/** Can be used to display how many total retrieval requests might be available. */
	default void setTotalRetrievalPositionCount(int newCount) {  }
	
}
