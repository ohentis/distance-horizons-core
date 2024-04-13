package com.seibel.distanthorizons.core.util;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV1;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.coreapi.ModInfo;
import org.jetbrains.annotations.Contract;

/**
 * A helper class that is used to access the data from a long
 * formatted as a full data point. <br>
 * A full data point contains the most information and is the
 * source of truth used when creating render data. <br><br>
 *
 * Specifically used by the data sources: <br>
 * - {@link FullDataSourceV2} <br>
 * - {@link FullDataSourceV1} aka CompleteFullDataSource <br>
 * - (Deleted) HighDetailIncompleteFullDataSource aka SparseDataSource <br>
 * - (Deleted) LowDetailIncompleteFullDataSource aka SpottyDataSource <br><br>
 *
 * <strong>DataPoint Format: </strong><br>
 * <code>
 * ID: blockState id <br>
 * MY: Min Y Height (unsigned, relative to the minimum level height) <br>
 * HI: Height (how tall this data point is in blocks) <br>
 * BL: Block light <br>
 * SL: Sky light <br><br>
 *
 * =======Bit layout=======	<br>
 * SL SL SL SL  BL BL BL BL <-- Top bits <br>
 * MY MY MY MY  MY MY MY MY	<br>
 * MY MY MY MY  HI HI HI HI	<br>
 * HI HI HI HI  HI HI HI HI	<br>
 * ID ID ID ID  ID ID ID ID	<br>
 * ID ID ID ID  ID ID ID ID	<br>
 * ID ID ID ID  ID ID ID ID	<br>
 * ID ID ID ID  ID ID ID ID <-- Bottom bits	<br>
 * </code>
 *
 * @see FullDataSourceV1
 * @see FullDataSourceV2
 */
public class FullDataPointUtil
{
	public static final boolean RUN_VALIDATION = ModInfo.IS_DEV_BUILD;
	
	/** Represents the data held by an empty data point */
	public static final int EMPTY_DATA_POINT = 0;
	
	public static final int ID_WIDTH = 32;
	public static final int HEIGHT_WIDTH = 12;
	public static final int MIN_Y_WIDTH = 12;
	public static final int SKY_LIGHT_WIDTH = 4;
	public static final int BLOCK_LIGHT_WIDTH = 4;
	
	public static final int ID_OFFSET = 0;
	public static final int HEIGHT_OFFSET = ID_OFFSET + ID_WIDTH;
	/** indicates the Y position where the LOD starts relative to the level's minimum height */
	public static final int MIN_Y_OFFSET = HEIGHT_OFFSET + HEIGHT_WIDTH;
	public static final int SKY_LIGHT_OFFSET = MIN_Y_OFFSET + MIN_Y_WIDTH;
	public static final int BLOCK_LIGHT_OFFSET = SKY_LIGHT_OFFSET + SKY_LIGHT_WIDTH;
	
	
	public static final long ID_MASK = Integer.MAX_VALUE;
	public static final long INVERSE_ID_MASK = ~ID_MASK;
	public static final int HEIGHT_MASK = (int) Math.pow(2, HEIGHT_WIDTH) - 1;
	public static final int MIN_Y_MASK = (int) Math.pow(2, MIN_Y_WIDTH) - 1;
	public static final int SKY_LIGHT_MASK = (int) Math.pow(2, SKY_LIGHT_WIDTH) - 1;
	public static final int BLOCK_LIGHT_MASK = (int) Math.pow(2, BLOCK_LIGHT_WIDTH) - 1;
	
	
	/**
	 * creates a new datapoint with the given values 
	 * @param relMinY relative to the minimum level Y value
	 */
	public static long encode(int id, int height, int relMinY, byte blockLight, byte skyLight)
	{
		if (RUN_VALIDATION)
		{
			// assertions are inside if-blocks to prevent unnecessary string concatenations
			if (relMinY < 0 || relMinY >= RenderDataPointUtil.MAX_WORLD_Y_SIZE)
			{
				LodUtil.assertNotReach("Trying to create datapoint with y[" + relMinY + "] out of range!");
			}
			if (height <= 0 || height >= RenderDataPointUtil.MAX_WORLD_Y_SIZE)
			{
				LodUtil.assertNotReach("Trying to create datapoint with height[" + height + "] out of range!");
			}
			if (relMinY + height > RenderDataPointUtil.MAX_WORLD_Y_SIZE)
			{
				LodUtil.assertNotReach("Trying to create datapoint with y+depth[" + (relMinY + height) + "] out of range!");
			}
		}
		
		
		long data = 0;
		data |= id & ID_MASK;
		data |= (long) (height & HEIGHT_MASK) << HEIGHT_OFFSET;
		data |= (long) (relMinY & MIN_Y_MASK) << MIN_Y_OFFSET;
		data |= (long) blockLight << BLOCK_LIGHT_OFFSET;
		data |= (long) skyLight << SKY_LIGHT_OFFSET;
		
		
		if (RUN_VALIDATION)
		{
			if (getId(data) != id || getHeight(data) != height || getBottomY(data) != relMinY
				  || getBlockLight(data) != Byte.toUnsignedInt(blockLight) || getSkyLight(data) != Byte.toUnsignedInt(skyLight))
			{
				LodUtil.assertNotReach(
						"Trying to create datapoint with " +
								"id[" + id + "], height[" + height + "], minY[" + relMinY + "], blockLight[" + blockLight + "], skyLight[" + skyLight + "] " +
								"but got " +
								"id[" + getId(data) + "], height[" + getHeight(data) + "], minY[" + getBottomY(data) + "], blockLight[" + getBlockLight(data) + "], skyLight[" + getSkyLight(data) + "]!");
			}
		}
		
		return data;
	}
	
	
	/** Returns the BlockState/Biome pair ID used to identify this LOD's color */
	public static int getId(long data) { return (int) (data & ID_MASK); }
	/** Returns how many blocks tall this LOD is. */
	public static int getHeight(long data) { return (int) ((data >> HEIGHT_OFFSET) & HEIGHT_MASK); }
	/**
	 * Returns the unsigned block position of the bottom vertices for this LOD relative to the level's minimum height. 
	 * Should be between 0 and {@link RenderDataPointUtil#MAX_WORLD_Y_SIZE}
	 */
	public static int getBottomY(long data) { return (int) ((data >> MIN_Y_OFFSET) & MIN_Y_MASK); }
	public static int getBlockLight(long data) { return (int) ((data >> BLOCK_LIGHT_OFFSET) & BLOCK_LIGHT_MASK); }
	public static int getSkyLight(long data) { return (int) ((data >> SKY_LIGHT_OFFSET) & SKY_LIGHT_MASK); }
	
	public static long setBlockLight(long data, byte blockLight) { return (data & ~((long) BLOCK_LIGHT_MASK << BLOCK_LIGHT_OFFSET) | (long) blockLight << BLOCK_LIGHT_OFFSET); }
	
	public static String toString(long data) { return "[ID:" + getId(data) + ",Y:" + getBottomY(data) + ",Height:" + getHeight(data) + ",BlockLight:" + getBlockLight(data) + ",SkyLight:" + getSkyLight(data) + "]"; }
	
	
	
	/** Remaps the biome/blockState ID of the given datapoint */
	@Contract(pure = true)
	public static long remap(int[] newIdMapping, long data) throws IndexOutOfBoundsException
	{
		int currentId = getId(data);
		try
		{
			int newId = newIdMapping[currentId];
			return (data & INVERSE_ID_MASK) | newId;
		}
		catch (IndexOutOfBoundsException e)
		{
			// this try-catch is present to fix an issue where the stack trace is missing
			// and to allow for easily attaching a debugger
			
			// if this was thrown that probably means the datasource has been
			// re-mapped multiple times, causing the ID's to go out of their expected bounds.
			throw new RuntimeException(e);
		}
	}
	
}
