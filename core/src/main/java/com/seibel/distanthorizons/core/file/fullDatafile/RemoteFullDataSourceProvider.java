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
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.file.structure.ISaveStructure;
import com.seibel.distanthorizons.core.generation.RemoteWorldRetrievalQueue;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenResult;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.level.WorldGenModule;
import com.seibel.distanthorizons.core.multiplayer.client.SyncOnLoadRequestQueue;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Only handles {@link SyncOnLoadRequestQueue} requests (IE updating existing LODs based on a timestamp).
 * Missing data is handled by {@link WorldGenModule} and {@link RemoteWorldRetrievalQueue}.
 */
public class RemoteFullDataSourceProvider extends GeneratedFullDataSourceProvider
{
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	
	@Nullable
	private final SyncOnLoadRequestQueue syncOnLoadRequestQueue;
	private final Set<Long> visitedPositions = ConcurrentHashMap.newKeySet();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public RemoteFullDataSourceProvider(
			IDhLevel level, ISaveStructure saveStructure, @Nullable File saveDirOverride, 
			@Nullable SyncOnLoadRequestQueue syncOnLoadRequestQueue)
	{
		super(level, saveStructure, saveDirOverride);
		this.syncOnLoadRequestQueue = syncOnLoadRequestQueue;
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
		
		Long timestamp = this.getTimestampForPos(pos);
		if (timestamp != null)
		{
			this.syncOnLoadRequestQueue.submitRequest(pos, timestamp, this.delayedFullDataSourceSaveCache::queueDataSourceForUpdateAndSave);
		}
		
		return super.get(pos);
	}
	
	
	@Override
	public CompletableFuture<WorldGenResult> queuePositionForRetrieval(long genPos, boolean allowAboveMaxGenRequests)
	{
		RemoteWorldRetrievalQueue worldGenQueue = (RemoteWorldRetrievalQueue) this.worldGenQueueRef.get();
		if (worldGenQueue == null)
		{
			return null;
		}
		
		return super.queuePositionForRetrieval(genPos, worldGenQueue.isPosCloserThanFarthestWaiting(new DhBlockPos2D(MC_CLIENT.getPlayerBlockPos()), genPos));
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