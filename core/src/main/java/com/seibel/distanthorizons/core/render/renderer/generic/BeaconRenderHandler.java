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

package com.seibel.distanthorizons.core.render.renderer.generic;

import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBoxGroupShading;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import com.seibel.distanthorizons.core.sql.repo.BeaconBeamRepo;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class BeaconRenderHandler
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	
	/** if this is null then the other handler is probably null too, but just in case */
	private final BeaconBeamRepo beaconBeamRepo;
	
	private final IDhApiRenderableBoxGroup beaconBoxGroup;
	private final HashMap<DhBlockPos, AtomicInteger> beaconRefCountByBlockPos = new HashMap<>();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public BeaconRenderHandler(@NotNull BeaconBeamRepo beaconBeamRepo, @NotNull GenericObjectRenderer renderer) 
	{
		this.beaconBeamRepo = beaconBeamRepo;
		
		this.beaconBoxGroup = GenericRenderObjectFactory.INSTANCE.createAbsolutePositionedGroup(new ArrayList<>(0));
		this.beaconBoxGroup.setBlockLight(LodUtil.MAX_MC_LIGHT);
		this.beaconBoxGroup.setSkyLight(LodUtil.MAX_MC_LIGHT);
		this.beaconBoxGroup.setShading(DhApiRenderableBoxGroupShading.getUnshaded());
		this.beaconBoxGroup.setPreRenderFunc((renderEventParam) -> this.beaconBoxGroup.setActive(Config.Client.Advanced.Graphics.GenericRendering.enableBeaconRendering.get()));
		
		renderer.add(this.beaconBoxGroup);
	}
	
	
	
	//=========================//
	// level loading/unloading //
	//=========================//
	
	public void setBeaconBeamsForChunk(DhChunkPos chunkPos, java.util.List<BeaconBeamDTO> newBeamList)
	{
		// synchronized to prevent two threads from updating the same chunk at the same time
		synchronized (this)
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
			java.util.List<BeaconBeamDTO> existingBeamList = this.beaconBeamRepo.getAllBeamsForPos(chunkPos);
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
					// beam no longer exists at position, remove from DB
					this.beaconBeamRepo.deleteWithKey(beaconPos); // TODO broken when updating adjacent chunks
					this.stopRenderingBeaconAtPos(beaconPos);
				}
				
			}
		}
	}
	
	public void loadBeaconBeamsInPos(long pos)
	{
		// get all beams in pos
		List<BeaconBeamDTO> existingBeamList = this.beaconBeamRepo.getAllBeamsForPos(pos);
		for (int i = 0; i < existingBeamList.size(); i++)
		{
			BeaconBeamDTO newBeam = existingBeamList.get(i);
			this.startRenderingBeacon(newBeam);
		}
	}
	
	public void unloadBeaconBeamsInPos(long pos)
	{
		// get all beams in pos
		List<BeaconBeamDTO> existingBeamList = this.beaconBeamRepo.getAllBeamsForPos(pos);
		for (int i = 0; i < existingBeamList.size(); i++)
		{
			BeaconBeamDTO beam = existingBeamList.get(i);
			this.stopRenderingBeaconAtPos(beam.pos);
		}
	}
	
	
	
	//=================//
	// render handling //
	//=================//
	
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
	
}
