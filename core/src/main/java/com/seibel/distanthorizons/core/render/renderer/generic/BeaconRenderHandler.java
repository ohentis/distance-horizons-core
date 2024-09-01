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

import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBoxGroupShading;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import com.seibel.distanthorizons.core.sql.repo.BeaconBeamRepo;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.math.Vec3d;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.coreapi.ModInfo;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

public class BeaconRenderHandler
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	private static final int BEAM_TOP_Y = 6_000;
	
	/** how often should we check if a beacon should be culled? */
	private static final int MAX_CULLING_FREQUENCY_IN_MS = 1_000;
	
	
	
	/** if this is null then the other handler is probably null too, but just in case */
	private final BeaconBeamRepo beaconBeamRepo;
	
	private final ReentrantLock updateLock = new ReentrantLock();
	
	private final IDhApiRenderableBoxGroup beaconBoxGroup;
	private final ArrayList<DhApiRenderableBox> fullBeaconBoxList = new ArrayList<>();
	private final HashSet<DhBlockPos> beaconBlockPosSet = new HashSet<>();
	
	private boolean cullingThreadRunning = false;
	private boolean updateRenderDataNextFrame = false;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public BeaconRenderHandler(@NotNull BeaconBeamRepo beaconBeamRepo, @NotNull GenericObjectRenderer renderer) 
	{
		this.beaconBeamRepo = beaconBeamRepo;
		
		this.beaconBoxGroup = GenericRenderObjectFactory.INSTANCE.createAbsolutePositionedGroup(ModInfo.NAME+":Beacons", new ArrayList<>(0));
		this.beaconBoxGroup.setBlockLight(LodUtil.MAX_MC_LIGHT);
		this.beaconBoxGroup.setSkyLight(LodUtil.MAX_MC_LIGHT);
		this.beaconBoxGroup.setSsaoEnabled(false);
		this.beaconBoxGroup.setShading(DhApiRenderableBoxGroupShading.getUnshaded());
		this.beaconBoxGroup.setPreRenderFunc(this::beforeRender);
		
		renderer.add(this.beaconBoxGroup);
	}
	
	
	
	//=========================//
	// level loading/unloading //
	//=========================//
	
	public void setBeaconBeamsForChunk(DhChunkPos chunkPos, List<BeaconBeamDTO> newBeamList)
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
				newBeamByPos.put(beam.blockPos, beam);
				allPosSet.add(beam.blockPos);
			}
			
			// get existing beams
			List<BeaconBeamDTO> existingBeamList = this.beaconBeamRepo.getAllBeamsForPos(chunkPos);
			HashMap<DhBlockPos, BeaconBeamDTO> existingBeamByPos = new HashMap<>(existingBeamList.size());
			for (int i = 0; i < existingBeamList.size(); i++)
			{
				BeaconBeamDTO beam = existingBeamList.get(i);
				existingBeamByPos.put(beam.blockPos, beam);
				allPosSet.add(beam.blockPos);
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
					// beam still exists in chunk
					if (!existingBeam.color.equals(newBeam.color))
					{
						// beam colors were changed
						this.beaconBeamRepo.save(newBeam);
						this.updateBeaconColor(newBeam);
					}
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
			this.stopRenderingBeaconAtPos(beam.blockPos);
		}
	}
	
	
	
	//=================//
	// render handling //
	//=================//
	
	private void startRenderingBeacon(BeaconBeamDTO beacon)
	{
		try
		{
			this.updateLock.lock();
			
			if (this.beaconBlockPosSet.add(beacon.blockPos))
			{
				DhApiRenderableBox beaconBox = new DhApiRenderableBox(
						new DhApiVec3d(beacon.blockPos.getX(), beacon.blockPos.getY() + 1, beacon.blockPos.getZ()),
						new DhApiVec3d(beacon.blockPos.getX() + 1, BEAM_TOP_Y, beacon.blockPos.getZ() + 1),
						beacon.color,
						EDhApiBlockMaterial.ILLUMINATED
				);
				
				this.beaconBoxGroup.add(beaconBox);
				this.fullBeaconBoxList.add(beaconBox);
				this.beaconBoxGroup.triggerBoxChange();
			}
		}
		finally
		{
			this.updateLock.unlock();
		}
	}
	
	private void stopRenderingBeaconAtPos(DhBlockPos beaconPos)
	{
		try
		{
			this.updateLock.lock();
			
			if (this.beaconBlockPosSet.remove(beaconPos))
			{
				Predicate<DhApiRenderableBox> removePredicate = (DhApiRenderableBox box) ->
				{
					return box.minPos.x == beaconPos.getX()
							&& box.minPos.y == beaconPos.getY() + 1 // plus 1 because the beam starts above the beacon
							&& box.minPos.z == beaconPos.getZ();
				};
				this.beaconBoxGroup.removeIf(removePredicate);
				this.fullBeaconBoxList.removeIf(removePredicate);
				
				this.beaconBoxGroup.triggerBoxChange();
			}
		}
		finally
		{
			this.updateLock.unlock();
		}
	}
	
	private void updateBeaconColor(BeaconBeamDTO newBeam)
	{
		try
		{
			this.updateLock.lock();
			
			DhBlockPos pos = newBeam.blockPos;
			for (int i = 0; i < this.fullBeaconBoxList.size(); i++)
			{
				DhApiRenderableBox box = this.fullBeaconBoxList.get(i);
				if (box.minPos.x == pos.getX()
						&& box.minPos.y == pos.getY() + 1 // plus 1 because the beam starts above the beacon
						&& box.minPos.z == pos.getZ())
				{
					box.color = newBeam.color;
					this.beaconBoxGroup.triggerBoxChange();
					break;
				}
			}
		}
		finally
		{
			this.updateLock.unlock();
		}
	}
	
	
	private void beforeRender(DhApiRenderParam renderEventParam) 
	{
		// this could be called only when the player moves, but it's an extremely cheap check, 
		// so there isn't much of a reason to bother 
		this.tryUpdateBeaconCullingAsync();
		
		// this must be called on the render thread to prevent concurrency issues
		if (this.updateRenderDataNextFrame)
		{
			this.beaconBoxGroup.triggerBoxChange();
			this.updateRenderDataNextFrame = false;
		}
		this.beaconBoxGroup.setActive(Config.Client.Advanced.Graphics.GenericRendering.enableBeaconRendering.get());
	}
	/** does nothing if the culling thread is already running */
	private void tryUpdateBeaconCullingAsync()
	{
		ThreadPoolExecutor executor = ThreadPoolUtil.getBeaconCullingExecutor();
		if (executor != null 
			&& !this.cullingThreadRunning)
		{
			this.cullingThreadRunning = true;
			
			try
			{
				executor.execute(() ->
				{
					try
					{
						Thread.sleep(MAX_CULLING_FREQUENCY_IN_MS);
					}
					catch (InterruptedException ignore)
					{
					}
					
					try
					{
						// lock to make sure we don't try adding beacons to the arrays while processing them
						this.updateLock.lock();
						
						Vec3d cameraPos = MC_RENDER.getCameraExactPosition();
						double mcRenderDistance = MC_RENDER.getRenderDistance() * LodUtil.CHUNK_WIDTH;
						
						
						// Clear the existing box group so we can re-populate it.
						// Since the box group is only used when we trigger an update, clearing it here
						// and repopulating it is fine.
						this.beaconBoxGroup.clear();
						
						// While iterating over every beacon isn't a great way of doing this, 
						// when 940 beacons were tested this only took ~0.9 Milliseconds, so as long as
						// we aren't freezing the render thread this method of culling works just fine.
						for (DhApiRenderableBox box : this.fullBeaconBoxList)
						{
							// if a beacon is outside the vanilla render distance render it
							double distance = Vec3d.getDistance(cameraPos, box.minPos);
							if (distance > mcRenderDistance)
							{
								this.beaconBoxGroup.add(box);
							}
						}
						
						this.updateRenderDataNextFrame = true;
					}
					catch (Exception e)
					{
						LOGGER.error("Unexpected issue while updating beacon culling. Error: " + e.getMessage(), e);
					}
					finally
					{
						this.updateLock.unlock();
						this.cullingThreadRunning = false;
					}
				});
			}
			catch (RejectedExecutionException ignore)
			{ /* If this happens that means everything is already shut down and no culling is necessary */ }
		}
	}
	
	
}
