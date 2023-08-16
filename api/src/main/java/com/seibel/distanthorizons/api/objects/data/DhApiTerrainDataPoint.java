package com.seibel.distanthorizons.api.objects.data;

import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;

/**
 * Holds a single datapoint of terrain data.
 *
 * @author James Seibel
 * @version 2022-11-13
 */
public class DhApiTerrainDataPoint
{
	/**
	 * 0 = block <br>
	 * 1 = 2x2 blocks <br>
	 * 2 = 4x4 blocks <br>
	 * 4 = chunk (16x16 blocks) <br>
	 * 9 = region (512x512 blocks) <br>
	 */
	public final byte detailLevel;
	
	public final int lightLevel;
	public final int topYBlockPos;
	public final int bottomYBlockPos;
	
	public final IDhApiBlockStateWrapper blockStateWrapper;
	public final IDhApiBiomeWrapper biomeWrapper;
	
	
	
	public DhApiTerrainDataPoint(byte detailLevel, int lightLevel, int topYBlockPos, int bottomYBlockPos, IDhApiBlockStateWrapper blockStateWrapper, IDhApiBiomeWrapper biomeWrapper)
	{
		this.detailLevel = detailLevel;
		
		this.lightLevel = lightLevel;
		this.topYBlockPos = topYBlockPos;
		this.bottomYBlockPos = bottomYBlockPos;
		
		this.blockStateWrapper = blockStateWrapper;
		this.biomeWrapper = biomeWrapper;
	}
	
}
