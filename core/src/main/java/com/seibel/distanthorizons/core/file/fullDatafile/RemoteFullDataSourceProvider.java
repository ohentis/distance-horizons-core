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
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.level.WorldGenModule;
import com.seibel.distanthorizons.core.multiplayer.client.SyncOnLoadRequestQueue;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Only handles {@link SyncOnLoadRequestQueue} requests (IE updating existing LODs based on a timestamp).
 * Missing data is handled by {@link WorldGenModule} and {@link RemoteWorldRetrievalQueue}.
 */
public class RemoteFullDataSourceProvider extends GeneratedFullDataSourceProvider
{
	@Nullable
	private final SyncOnLoadRequestQueue syncOnLoadRequestQueue;
	private final Set<Long> visitedPositions = ConcurrentHashMap.newKeySet();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public RemoteFullDataSourceProvider(
			IDhClientLevel level, ISaveStructure saveStructure, @Nullable File saveDirOverride,
			@Nullable SyncOnLoadRequestQueue syncOnLoadRequestQueue)
	{
		super(level, saveStructure, saveDirOverride);
		this.syncOnLoadRequestQueue = syncOnLoadRequestQueue;
	}
	
	
	@Override
	public boolean queuePositionForRetrieval(Long genPos)
	{
		if (this.syncOnLoadRequestQueue == null)
		{
			return super.queuePositionForRetrieval(genPos);
		}
		
		int maxGenerationRequestDistance = this.syncOnLoadRequestQueue.networkState.sessionConfig.getMaxGenerationRequestDistance();
		DhBlockPos2D targetPos = this.level.getTargetPosForGeneration();
		if (targetPos == null || DhSectionPos.getChebyshevSignedBlockDistance(genPos, targetPos) / 16 > maxGenerationRequestDistance)
		{
			return false;
		}
		
		return super.queuePositionForRetrieval(genPos);
	}
	
	
	//==================//
	// override methods //
	//==================//
	
	@Override
	@Nullable
	public FullDataSourceV2 get(long pos)
	{
		if (this.syncOnLoadRequestQueue == null)
		{
			// we have local data, but networking is unavailable.
			return super.get(pos);
		}
		
		if (!this.visitedPositions.add(pos))
		{
			// This position has already been accessed before
			return super.get(pos);
		}
		
		
		//===========================//
		// request timestamp updates //
		// from server               //
		//===========================//
		
		// get the timestamp for every maximum detail position in this section
		int posToMinimumDetailScale = BitShiftUtil.powerOfTwo(DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		Map<Long, Long> timestamps = this.getTimestampsForRange(
				DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL,
				DhSectionPos.getX(pos) * posToMinimumDetailScale,
				DhSectionPos.getZ(pos) * posToMinimumDetailScale,
				(DhSectionPos.getX(pos) + 1) * posToMinimumDetailScale,
				(DhSectionPos.getZ(pos) + 1) * posToMinimumDetailScale
		);
		
		DhSectionPos.forEachChildAtDetailLevel(pos, DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL, childPos ->
		{
			int maxSyncOnLoadDistance = this.syncOnLoadRequestQueue.networkState.sessionConfig.getMaxSyncOnLoadDistance();
			DhBlockPos2D targetPos = this.level.getTargetPosForGeneration();
			if (targetPos == null || DhSectionPos.getChebyshevSignedBlockDistance(childPos, targetPos) / 16 > maxSyncOnLoadDistance)
			{
				return;
			}
			
			if (!this.visitedPositions.add(childPos))
			{
				return;
			}
			
			// check if the server has newer versions of these LODs
			Long subTimestamp = timestamps.get(childPos);
			if (subTimestamp != null)
			{
				this.syncOnLoadRequestQueue.submitRequest(childPos, subTimestamp, this.delayedFullDataSourceSaveCache::queueDataSourceForUpdateAndSave);
			}
		});
		
		return super.get(pos);
	}
	
	
	
	//==========//
	// shutdown //
	//==========//
	
	@Override
	public void close()
	{
		if (this.syncOnLoadRequestQueue != null)
		{
			this.syncOnLoadRequestQueue.close();
		}
		super.close();
	}
	
}