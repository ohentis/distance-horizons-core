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

import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkModifiedEvent;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBoxGroupShading;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dataObjects.transformers.ChunkToLodBuilder;
import com.seibel.distanthorizons.core.file.fullDatafile.DelayedFullDataSourceSaveCache;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.generic.CloudRenderHandler;
import com.seibel.distanthorizons.core.render.renderer.generic.GenericObjectRenderer;
import com.seibel.distanthorizons.core.render.renderer.generic.GenericRenderObjectFactory;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import com.seibel.distanthorizons.core.sql.dto.ChunkHashDTO;
import com.seibel.distanthorizons.core.sql.repo.BeaconBeamRepo;
import com.seibel.distanthorizons.core.sql.repo.ChunkHashRepo;
import com.seibel.distanthorizons.core.util.LodUtil;
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
	
	public final ChunkToLodBuilder chunkToLodBuilder;
	
	/** if this is null then the other handler is probably null too, but just in case */
	@Nullable
	public ChunkHashRepo chunkHashRepo;
	/** if this is null then the other handler is probably null too, but just in case */
	@Nullable
	public BeaconBeamRepo beaconBeamRepo;
	
	protected final DelayedFullDataSourceSaveCache delayedFullDataSourceSaveCache = new DelayedFullDataSourceSaveCache(this::onDataSourceSave, 2_000);
	/** contains the {@link DhChunkPos} for each {@link DhSectionPos} that are queued to save via {@link AbstractDhLevel#delayedFullDataSourceSaveCache} */
	protected final ConcurrentHashMap<Long, HashSet<DhChunkPos>> updatedChunkPosSetBySectionPos = new ConcurrentHashMap<>();
	
	protected final IDhApiRenderableBoxGroup beaconBoxGroup;
	protected final HashMap<DhBlockPos, AtomicInteger> beaconRefCountByBlockPos = new HashMap<>();
	
	protected boolean beaconGroupBound = false;
	
	/** Will be null if clouds shouldn't be rendered for this level. */
	@Nullable
	protected CloudRenderHandler cloudRenderHandler;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	protected AbstractDhLevel() 
	{ 
		this.chunkToLodBuilder = new ChunkToLodBuilder();
		
		this.beaconBoxGroup = GenericRenderObjectFactory.INSTANCE.createAbsolutePositionedGroup(new ArrayList<>(0));
		this.beaconBoxGroup.setBlockLight(LodUtil.MAX_MC_LIGHT);
		this.beaconBoxGroup.setSkyLight(LodUtil.MAX_MC_LIGHT);
		this.beaconBoxGroup.setShading(DhApiRenderableBoxGroupShading.getUnshaded());
		this.beaconBoxGroup.setPreRenderFunc((renderEventParam) -> this.beaconBoxGroup.setActive(Config.Client.Advanced.Graphics.GenericRendering.enableBeaconRendering.get()));
	}
	
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
	
	
	
	//=================//
	// default methods //
	//=================//
	
	@Override
	public int getUnsavedDataSourceCount() { return this.delayedFullDataSourceSaveCache.getUnsavedCount(); }
	
	@Override
	public void updateChunkAsync(IChunkWrapper chunkWrapper)
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
	@Override
	public void setChunkHash(DhChunkPos pos, int chunkHash)
	{
		if (this.chunkHashRepo != null)
		{
			this.chunkHashRepo.save(new ChunkHashDTO(pos, chunkHash));
		}
	}
	
	
	
	//=================//
	// beacon handling //
	//=================//
	
	@Override
	public List<BeaconBeamDTO> getAllBeamsForSectionPos(long pos)
	{
		if (this.beaconBeamRepo != null)
		{
			return this.beaconBeamRepo.getAllBeamsForPos(pos);
		}
		else
		{
			return new ArrayList<>(0);
		}
	}
	
	
	@Override
	public void setBeaconBeamsForChunk(DhChunkPos chunkPos, List<BeaconBeamDTO> newBeamList)
	{
		// synchronized to prevent two threads from updating the same chunk at the same time
		synchronized (this)
		{
			GenericObjectRenderer genericObjectRenderer = this.getGenericRenderer();
			
			// should always be non-null, but just in case
			if (this.beaconBeamRepo != null
				&& genericObjectRenderer != null)
			{
				HashSet<DhBlockPos> allPosSet = new HashSet<>();
				
				// sort new beams
				HashMap<DhBlockPos, BeaconBeamDTO> newBeamByPos = new HashMap<>(newBeamList.size());
				for (int i = 0; i < newBeamList.size(); i++)
				{
					BeaconBeamDTO beam = newBeamList.get(i);
					newBeamByPos.put(beam.pos, beam);
					allPosSet.add(beam.pos);
				}
				
				// get existing beams
				List<BeaconBeamDTO> existingBeamList = this.beaconBeamRepo.getAllBeamsForPos(chunkPos);
				HashMap<DhBlockPos, BeaconBeamDTO> existingBeamByPos = new HashMap<>(existingBeamList.size());
				for (int i = 0; i < existingBeamList.size(); i++)
				{
					BeaconBeamDTO beam = existingBeamList.get(i);
					existingBeamByPos.put(beam.pos, beam);
					allPosSet.add(beam.pos);
				}
				
				
				
				for (DhBlockPos beaconPos : allPosSet)
				{
					if (!chunkPos.contains(beaconPos))
					{
						// don't update beacons outside the updated chunk
						continue;
					}
					
					BeaconBeamDTO existingBeam = existingBeamByPos.get(beaconPos);
					BeaconBeamDTO newBeam = newBeamByPos.get(beaconPos);
					
					if (existingBeam != null && newBeam != null)
					{
						// beam still exists in chunk, do nothing
					}
					else if (existingBeam == null && newBeam != null)
					{
						// new beam found, add to DB
						this.beaconBeamRepo.save(newBeam);
						this.startRenderingBeacon(newBeam);
					}
					else if (existingBeam != null && newBeam == null)
					{
						// beam no longer exists at position, remove
						this.beaconBeamRepo.deleteWithKey(beaconPos); // TODO broken when updating adjacent chunks
						this.stopRenderingBeaconAtPos(beaconPos);
					}
					
				}
				
			}
		}
	}
	
	@Override
	public void loadBeaconBeamsInPos(long pos)
	{
		GenericObjectRenderer genericObjectRenderer = this.getGenericRenderer();
		
		// should always be non-null, but just in case
		if (this.beaconBeamRepo != null
			&& genericObjectRenderer != null)
		{
			// generic setup is delayed to allow for the main renderer to start up
			if (!this.beaconGroupBound)
			{
				this.beaconGroupBound = true;
				genericObjectRenderer.add(this.beaconBoxGroup);
				
				if (!this.getLevelWrapper().hasCeiling()
					&& !this.getLevelWrapper().getDimensionType().isTheEnd())
				{
					this.cloudRenderHandler = new CloudRenderHandler(this, genericObjectRenderer);
				}
			}
			
			
			// get beams in pos
			List<BeaconBeamDTO> existingBeamList = this.beaconBeamRepo.getAllBeamsForPos(pos);
			for (int i = 0; i < existingBeamList.size(); i++)
			{
				BeaconBeamDTO newBeam = existingBeamList.get(i);
				this.startRenderingBeacon(newBeam);
			}
		}
	}
	
	@Override
	public void unloadBeaconBeamsInPos(long pos)
	{
		GenericObjectRenderer genericObjectRenderer = this.getGenericRenderer();
		
		// should always be non-null, but just in case
		if (this.beaconBeamRepo != null
			&& genericObjectRenderer != null)
		{
			// get beams in pos
			List<BeaconBeamDTO> existingBeamList = this.beaconBeamRepo.getAllBeamsForPos(pos);
			for (int i = 0; i < existingBeamList.size(); i++)
			{
				BeaconBeamDTO beam = existingBeamList.get(i);
				this.stopRenderingBeaconAtPos(beam.pos);
			}
		}
	}
	
	private void startRenderingBeacon(BeaconBeamDTO beacon)
	{
		this.beaconRefCountByBlockPos.compute(beacon.pos, (beamPos, beaconRefCount) ->
		{
			if (beaconRefCount == null) { beaconRefCount = new AtomicInteger(); }
			if (beaconRefCount.getAndIncrement() == 0)
			{
				DhApiRenderableBox beaconBox = new DhApiRenderableBox(
						new DhApiVec3f(beacon.pos.x, beacon.pos.y+1, beacon.pos.z),
						new DhApiVec3f(beacon.pos.x+1, 6_000, beacon.pos.z+1),
						// TODO calculate color
						beacon.color
				);
				
				this.beaconBoxGroup.add(beaconBox);
				this.beaconBoxGroup.triggerBoxChange();
			}
			return beaconRefCount;
		});
	}
	
	private void stopRenderingBeaconAtPos(DhBlockPos beaconPos)
	{
		this.beaconRefCountByBlockPos.compute(beaconPos, (pos, beaconRefCount) ->
		{
			if (beaconRefCount != null
				&& beaconRefCount.decrementAndGet() <= 0)
			{
				this.beaconBoxGroup.removeIf((box) ->
						box.minPos.x == beaconPos.x
								&& box.minPos.y == beaconPos.y+1 // plus 1 because the beam starts above the beacon
								&& box.minPos.z == beaconPos.z
				);
				this.beaconBoxGroup.triggerBoxChange();
				return null;
			}
			else
			{
				return beaconRefCount;
			}
		});
	}
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public void close() 
	{ 
		this.chunkToLodBuilder.close();
		
		GenericObjectRenderer genericObjectRenderer = this.getGenericRenderer();
		if (genericObjectRenderer != null)
		{
			genericObjectRenderer.remove(this.beaconBoxGroup.getId());
			this.beaconGroupBound = false;
		}
	}
	
}
