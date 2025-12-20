/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
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

package com.seibel.distanthorizons.core.generation.tasks;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/**
 * @see DataSourceRetrievalTask
 */
public class DataSourceRetrievalResult
{
	/** true if terrain was generated */
	public final boolean success; // TODO reponse enum?
	/** the position that was generated, will be null if nothing was generated */
	public final long pos;
	@Nullable
	public final FullDataSourceV2 generatedDataSource;
	
	/** if a position is too high detail for world generator to handle it, these futures are for its 4 children positions after being split up. */
	public final ArrayList<CompletableFuture<DataSourceRetrievalResult>> childFutures = new ArrayList<>(4);
	
	
	
	//==============//
 	// constructors //
	//==============//
	
	public static DataSourceRetrievalResult CreateSplit(ArrayList<CompletableFuture<DataSourceRetrievalResult>> siblingFutures) { return new DataSourceRetrievalResult(false, 0, null, siblingFutures); }
	public static DataSourceRetrievalResult CreateFail() { return new DataSourceRetrievalResult(false, 0, null,null); }
	public static DataSourceRetrievalResult CreateSuccess(long pos, FullDataSourceV2 generatedDataSource) { return new DataSourceRetrievalResult(true, pos, generatedDataSource, null); }
	private DataSourceRetrievalResult(boolean success, long pos, @Nullable FullDataSourceV2 generatedDataSource, ArrayList<CompletableFuture<DataSourceRetrievalResult>> childFutures)
	{
		this.success = success;
		this.pos = pos;
		this.generatedDataSource = generatedDataSource;
		
		if (childFutures != null)
		{
			this.childFutures.addAll(childFutures);
		}
	}
	
	
}
