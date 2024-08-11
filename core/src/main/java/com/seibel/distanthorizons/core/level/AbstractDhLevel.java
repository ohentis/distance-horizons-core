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

package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkModifiedEvent;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.DelayedFullDataSourceSaveCache;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.generic.BeaconRenderHandler;
import com.seibel.distanthorizons.core.render.renderer.generic.CloudRenderHandler;
import com.seibel.distanthorizons.core.render.renderer.generic.GenericObjectRenderer;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import com.seibel.distanthorizons.core.sql.dto.ChunkHashDTO;
import com.seibel.distanthorizons.core.sql.repo.BeaconBeamRepo;
import com.seibel.distanthorizons.core.sql.repo.ChunkHashRepo;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class AbstractDhLevel implements IDhLevel
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	/** if this is null then the other handler is probably null too, but just in case */
	@Nullable
	public ChunkHashRepo chunkHashRepo;
	/** if this is null then the other handler is probably null too, but just in case */
	@Nullable
	public BeaconBeamRepo beaconBeamRepo;
	
	protected final DelayedFullDataSourceSaveCache delayedFullDataSourceSaveCache = new DelayedFullDataSourceSaveCache(this::onDataSourceSave, 2_000);
	/** contains the {@link DhChunkPos} for each {@link DhSectionPos} that are queued to save via {@link AbstractDhLevel#delayedFullDataSourceSaveCache} */
	protected final ConcurrentHashMap<Long, HashSet<DhChunkPos>> updatedChunkPosSetBySectionPos = new ConcurrentHashMap<>();
	protected final ConcurrentHashMap<DhChunkPos, Integer> updatedChunkHashesByChunkPos = new ConcurrentHashMap<>();
	
	/** Will be null if clouds shouldn't be rendered for this level. */
	@Nullable
	protected CloudRenderHandler cloudRenderHandler;
	protected BeaconRenderHandler beaconRenderHandler;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	protected AbstractDhLevel() {  }
	
	/** 
	 * Creating the repos requires access to the level file, which isn't
	 * available at constructor time.
	 */
	protected void createAndSetSupportingRepos(File databaseFile)
	{
		// chunk hash
		ChunkHashRepo newChunkHashRepo = null;
		try
		{
			newChunkHashRepo = new ChunkHashRepo("jdbc:sqlite", databaseFile);
		}
		catch (SQLException e)
		{
			LOGGER.error("Unable to create [ChunkHashRepo], error: ["+e.getMessage()+"].", e);
		}
		this.chunkHashRepo = newChunkHashRepo;
		
		
		// beacon beam
		BeaconBeamRepo newBeaconBeamRepo = null;
		try
		{
			newBeaconBeamRepo = new BeaconBeamRepo("jdbc:sqlite", databaseFile);
		}
		catch (SQLException e)
		{
			LOGGER.error("Unable to create [BeaconBeamRepo], error: ["+e.getMessage()+"].", e);
		}
		this.beaconBeamRepo = newBeaconBeamRepo;
	}
	
	/** handles any setup that needs the repos to be created */
	protected void runRepoReliantSetup()
	{
		GenericObjectRenderer genericRenderer = this.getGenericRenderer();
		if (genericRenderer != null)
		{
			// only add clouds for certain dimension types
			if (!this.getLevelWrapper().hasCeiling()
					&& !this.getLevelWrapper().getDimensionType().isTheEnd())
			{
				this.cloudRenderHandler = new CloudRenderHandler(this, genericRenderer);
			}
			
			
			// shouldn't happen, but just in case
			if (this.beaconBeamRepo != null)
			{
				this.beaconRenderHandler = new BeaconRenderHandler(this.beaconBeamRepo, genericRenderer);
			}
		}
	}
	
	
	
	//=================//
	// default methods //
	//=================//
	
	@Override
	public int getUnsavedDataSourceCount() { return this.delayedFullDataSourceSaveCache.getUnsavedCount(); }
	
	@Override
	public void updateChunkAsync(IChunkWrapper chunkWrapper, int chunkHash)
	{
		FullDataSourceV2 dataSource = FullDataSourceV2.createFromChunk(chunkWrapper);
		if (dataSource == null)
		{
			// This can happen if, among other reasons, a chunk save is superseded by a later event
			return;
		}
		
		
		this.updatedChunkPosSetBySectionPos.compute(dataSource.getPos(), (dataSourcePos, chunkPosSet) ->
		{
			if (chunkPosSet == null)
			{
				chunkPosSet = new HashSet<>();
			}
			chunkPosSet.add(chunkWrapper.getChunkPos());
			return chunkPosSet;
		});
		this.updatedChunkHashesByChunkPos.put(chunkWrapper.getChunkPos(), chunkHash);
		
		// batch updates to reduce overhead when flying around or breaking/placing a lot of blocks in an area
		this.delayedFullDataSourceSaveCache.queueDataSourceForUpdateAndSave(dataSource);
	}
	
	private void onDataSourceSave(FullDataSourceV2 fullDataSource)
	{
		this.updateDataSourcesAsync(fullDataSource).thenRun(() ->
		{
			HashSet<DhChunkPos> updatedChunkPosSet = this.updatedChunkPosSetBySectionPos.remove(fullDataSource.getPos());
			if (updatedChunkPosSet != null)
			{
				for (DhChunkPos chunkPos : updatedChunkPosSet)
				{
					// save after the data source has been updated to prevent saving the hash without the associated datasource
					Integer chunkHash = this.updatedChunkHashesByChunkPos.remove(chunkPos);
					if (this.chunkHashRepo != null && chunkHash != null)
					{
						this.chunkHashRepo.save(new ChunkHashDTO(chunkPos, chunkHash));
					}
					
					ApiEventInjector.INSTANCE.fireAllEvents(
							DhApiChunkModifiedEvent.class,
							new DhApiChunkModifiedEvent.EventParam(this.getLevelWrapper(), chunkPos.x, chunkPos.z));
				}
			}
		});
	}
	
	
	
	//=======//
	// repos //
	//=======//
	
	// chunk hash //
	
	@Override
	public int getChunkHash(DhChunkPos pos)
	{
		if (this.chunkHashRepo == null)
		{
			return 0;
		}
		
		ChunkHashDTO dto = this.chunkHashRepo.getByKey(pos);
		return (dto != null) ? dto.chunkHash : 0;
	}
	
	
	
	//=================//
	// beacon handling //
	//=================//
	
	@Override
	public void setBeaconBeamsForChunk(DhChunkPos chunkPos, List<BeaconBeamDTO> newBeamList)
	{
		if (this.beaconRenderHandler != null)
		{
			this.beaconRenderHandler.setBeaconBeamsForChunk(chunkPos, newBeamList);
		}
	}
	
	@Override
	public void loadBeaconBeamsInPos(long pos)
	{
		if (this.beaconRenderHandler != null)
		{
			this.beaconRenderHandler.loadBeaconBeamsInPos(pos);
		}
	}
	@Override
	public void unloadBeaconBeamsInPos(long pos)
	{
		if (this.beaconRenderHandler != null)
		{
			this.beaconRenderHandler.unloadBeaconBeamsInPos(pos);
		}
	}
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public void close() 
	{ 
		if (this.chunkHashRepo != null)
		{
			this.chunkHashRepo.close();
		}
		if (this.beaconBeamRepo != null)
		{
			this.beaconBeamRepo.close();
		}
	}
	
}