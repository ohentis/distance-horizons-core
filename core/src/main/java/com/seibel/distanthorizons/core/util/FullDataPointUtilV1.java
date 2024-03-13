package com.seibel.distanthorizons.core.util;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;

/**
 * <strong> Only for Legacy support </strong> <br>
 * Used by DH versions 2.0.0 and 2.0.1. <br><br>
 *
 * Specifically used by the data sources: <br>
 * - {@link CompleteFullDataSource} aka CompleteFullDataSource <br>
 * - (Deleted) HighDetailIncompleteFullDataSource <br>
 * - (Deleted) LowDetailIncompleteFullDataSource <br><br>
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
 * @see CompleteFullDataSource
 * @see FullDataPointUtil
 */
public class FullDataPointUtilV1
{
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
	 *
	 * @deprecated Should not be used anymore, just here as a reference for how the data points were constructed.
	 */
	@Deprecated
	public static long encode(int id, int height, int relMinY, byte blockLight, byte skyLight)
	{
		LodUtil.assertTrue(relMinY >= 0 && relMinY < RenderDataPointUtil.MAX_WORLD_Y_SIZE, "Trying to create datapoint with y["+relMinY+"] out of range!");
		LodUtil.assertTrue(height > 0 && height < RenderDataPointUtil.MAX_WORLD_Y_SIZE, "Trying to create datapoint with height["+height+"] out of range!");
		LodUtil.assertTrue(relMinY + height <= RenderDataPointUtil.MAX_WORLD_Y_SIZE, "Trying to create datapoint with y+depth["+(relMinY+height)+"] out of range!");
		
		long data = 0;
		data |= id & ID_MASK;
		data |= (long) (height & HEIGHT_MASK) << HEIGHT_OFFSET;
		data |= (long) (relMinY & MIN_Y_MASK) << MIN_Y_OFFSET;
		data |= (long) blockLight << BLOCK_LIGHT_OFFSET;
		data |= (long) skyLight << SKY_LIGHT_OFFSET;
		
		LodUtil.assertTrue(getId(data) == id && getHeight(data) == height && getBottomY(data) == relMinY && getBlockLight(data) == Byte.toUnsignedInt(blockLight) && getSkyLight(data) == Byte.toUnsignedInt(skyLight),
				"Trying to create datapoint with " +
						"id[" + id + "], height[" + height + "], minY[" + relMinY + "], blockLight[" + blockLight + "], skyLight[" + skyLight + "] " +
						"but got " +
						"id[" + getId(data) + "], height[" + getHeight(data) + "], minY[" + getBottomY(data) + "], blockLight[" + getBlockLight(data) + "], skyLight[" + getSkyLight(data) + "]!");
		
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
	
	
	public static String toString(long data) { return "[ID:" + getId(data) + ",Y:" + getBottomY(data) + ",Height:" + getHeight(data) + ",BlockLight:" + getBlockLight(data) + ",SkyLight:" + getSkyLight(data) + "]"; }
	
}
