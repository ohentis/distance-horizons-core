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

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.structure.ISaveStructure;
import com.seibel.distanthorizons.core.generation.RemoteWorldRetrievalQueue;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.level.WorldGenModule;
import com.seibel.distanthorizons.core.multiplayer.client.SyncOnLoginRequestQueue;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Only handles {@link SyncOnLoginRequestQueue} requests (IE updating existing LODs based on a timestamp).
 * Missing data is handled by {@link WorldGenModule} and {@link RemoteWorldRetrievalQueue}.
 */
public class RemoteFullDataSourceProvider extends GeneratedFullDataSourceProvider
{
	@Nullable
	private final SyncOnLoginRequestQueue syncOnLoginRequestQueue;
	private final Set<Long> finishedTaskPositions = ConcurrentHashMap.newKeySet();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public RemoteFullDataSourceProvider(
			IDhLevel level, ISaveStructure saveStructure, @Nullable File saveDirOverride, 
			@Nullable SyncOnLoginRequestQueue syncOnLoginRequestQueue)
	{
		super(level, saveStructure, saveDirOverride);
		this.syncOnLoginRequestQueue = syncOnLoginRequestQueue;
	}
	
	
	
	//==================//
	// override methods //
	//==================//
	
	@Override
	@Nullable
	public FullDataSourceV2 get(long pos)
	{
		//=======================//
		// get local data source //
		//=======================//
		
		FullDataSourceV2 fullDataSource = super.get(pos);
		if (fullDataSource == null)
		{
			// we don't have any local data for this position,
			// we can't queue updates based on a timestamp
			return null;	
		}
		
		if (this.syncOnLoginRequestQueue == null)
		{
			// we have local data, but aren't allowed to
			// request timestamp updates from the server.
			return fullDataSource;
		}
		
		
		
		//===========================//
		// request timestamp updates //
		// from server               //
		//===========================//
		
		// get the timestamp for every maximum detail position in this section
		int posToMinimumDetailScale = (DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL + 1);
		Map<Long, Long> timestamps = this.getTimestampsForRange(
				DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL,
				DhSectionPos.getX(pos) * posToMinimumDetailScale,
				DhSectionPos.getZ(pos) * posToMinimumDetailScale,
				(DhSectionPos.getX(pos) + 1) * posToMinimumDetailScale - 1,
				(DhSectionPos.getZ(pos) + 1) * posToMinimumDetailScale - 1
		);
		
		// check if the server has newer versions of these LODs
		for (Map.Entry<Long, Long> timestampBySectionPos : timestamps.entrySet())
		{
			Long subPos = timestampBySectionPos.getKey();
			Long subTimestamp = timestampBySectionPos.getValue();
			
			if (this.finishedTaskPositions.add(subPos))
			{
				this.syncOnLoginRequestQueue.submitRequest(subPos, subTimestamp, this.delayedFullDataSourceSaveCache::queueDataSourceForUpdateAndSave);
			}
		}
		
		return fullDataSource;
	}
	
	
	
	//==========//
	// shutdown //
	//==========//
	
	@Override
	public void close()
	{
		if (this.syncOnLoginRequestQueue != null)
		{
			this.syncOnLoginRequestQueue.close();
		}
		super.close();
	}
	
}