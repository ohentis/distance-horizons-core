package com.seibel.distanthorizons.core.file.beacon;

import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.render.renderer.generic.BeaconRenderHandler;
import com.seibel.distanthorizons.core.render.renderer.generic.GenericObjectRenderer;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import com.seibel.distanthorizons.core.sql.repo.BeaconBeamRepo;
import com.seibel.distanthorizons.core.util.KeyedLockContainer;
import com.seibel.distanthorizons.core.util.LodUtil;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class BeaconBeamDataHandler
{
	private final BeaconBeamRepo beaconBeamRepo;
	
	@Nullable
	private BeaconRenderHandler beaconRenderHandler;
	
	private final KeyedLockContainer<Long> updateLockContainer = new KeyedLockContainer<>();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public BeaconBeamDataHandler(@NotNull BeaconBeamRepo beaconBeamRepo, @Nullable GenericObjectRenderer renderer)
	{
		this.beaconBeamRepo = beaconBeamRepo;
		
		if (renderer != null)
		{
			this.beaconRenderHandler = new BeaconRenderHandler(renderer);
		}
	}
	
	
	
	//==========//
	// updating //
	//==========//
	
	public void setBeaconBeamsForChunk(DhChunkPos chunkPos, List<BeaconBeamDTO> activeBeamList)
	{
		long sectionPos = DhSectionPos.encode(LodUtil.CHUNK_DETAIL_LEVEL, chunkPos.getX(), chunkPos.getZ());
		this.setBeaconBeamsForPos(sectionPos, activeBeamList);
	}
	
	public void setBeaconBeamsForPos(long sectionPos, List<BeaconBeamDTO> activeBeamList)
	{
		// locked to prevent two threads from updating the same section at the same time
		ReentrantLock lock = this.updateLockContainer.getLockForPos(sectionPos);
		try
		{
			lock.lock();
			
			HashSet<DhBlockPos> allPosSet = new HashSet<>();
			
			// sort new beams
			HashMap<DhBlockPos, BeaconBeamDTO> activeBeamByPos = new HashMap<>(activeBeamList.size());
			for (BeaconBeamDTO beam : activeBeamList)
			{
				activeBeamByPos.put(beam.blockPos, beam);
				allPosSet.add(beam.blockPos);
			}
			
			// get existing beams
			List<BeaconBeamDTO> existingBeamList = this.beaconBeamRepo.getAllBeamsForPos(sectionPos);
			HashMap<DhBlockPos, BeaconBeamDTO> existingBeamByPos = new HashMap<>(existingBeamList.size());
			for (BeaconBeamDTO beam : existingBeamList)
			{
				existingBeamByPos.put(beam.blockPos, beam);
				allPosSet.add(beam.blockPos);
			}
			
			
			
			for (DhBlockPos beaconPos : allPosSet)
			{
				if (!DhSectionPos.contains(sectionPos, beaconPos))
				{
					// don't update beacons outside the updated chunk
					continue;
				}
				
				BeaconBeamDTO existingBeam = existingBeamByPos.get(beaconPos);
				BeaconBeamDTO activeBeam = activeBeamByPos.get(beaconPos);
				
				
				if (activeBeam != null)
				{
					if (existingBeam == null)
					{
						// new beam found, add to DB
						this.beaconBeamRepo.save(activeBeam);
						if (this.beaconRenderHandler != null)
						{
							this.beaconRenderHandler.startRenderingBeacon(activeBeam);
						}
					}
					else
					{
						// beam still exists in chunk
						if (!existingBeam.color.equals(activeBeam.color))
						{
							// beam colors were changed
							this.beaconBeamRepo.save(activeBeam);
							if (this.beaconRenderHandler != null)
							{
								this.beaconRenderHandler.updateBeaconColor(activeBeam);
							}
						}
					}
				}
				else if (existingBeam != null)
				{
					// beam no longer exists at position, remove from DB
					this.beaconBeamRepo.deleteWithKey(beaconPos);
					if (this.beaconRenderHandler != null)
					{
						this.beaconRenderHandler.stopRenderingBeaconAtPos(beaconPos);
					}
				}
				
			}
		}
		finally
		{
			lock.unlock();
		}
	}
	
	
	
	//===================//
	// loading/unloading //
	//===================//
	
	public void loadBeaconBeamsInPos(long pos)
	{
		if (this.beaconRenderHandler == null)
		{
			return;
		}
		
		// get all beams in pos
		List<BeaconBeamDTO> existingBeamList = this.beaconBeamRepo.getAllBeamsForPos(pos);
		for (BeaconBeamDTO newBeam : existingBeamList)
		{
			this.beaconRenderHandler.startRenderingBeacon(newBeam);
		}
	}
	
	public void unloadBeaconBeamsInPos(long pos)
	{
		if (this.beaconRenderHandler == null)
		{
			return;
		}
		
		// get all beams in pos
		List<BeaconBeamDTO> existingBeamList = this.beaconBeamRepo.getAllBeamsForPos(pos);
		for (BeaconBeamDTO beam : existingBeamList)
		{
			this.beaconRenderHandler.stopRenderingBeaconAtPos(beam.blockPos);
		}
	}
	
}
