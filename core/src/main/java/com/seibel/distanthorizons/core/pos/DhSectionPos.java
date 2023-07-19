package com.seibel.distanthorizons.core.pos;

import com.seibel.distanthorizons.core.enums.ELodDirection;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * The position object used to define LOD objects in the quad trees. <br>
 * 
 * A section contains 64 x 64 LOD columns at a given quality.
 * The Section detail level is different from the LOD detail level.
 * For the specifics of how they compare can be viewed in the constants {@link #SECTION_BLOCK_DETAIL_LEVEL},
 * {@link #SECTION_CHUNK_DETAIL_LEVEL}, and {@link #SECTION_REGION_DETAIL_LEVEL}).
 * 
 * @author Leetom
 * @version 2022-11-6
 */
public class DhSectionPos implements Comparable<DhSectionPos>
{
	/** 
	 * The lowest detail level a Section position can hold.
	 * This section DetailLevel holds 64 x 64 Block level (detail level 0) LODs. 
	 */
	public final static byte SECTION_MINIMUM_DETAIL_LEVEL = 6;
	
	public final static byte SECTION_BLOCK_DETAIL_LEVEL = SECTION_MINIMUM_DETAIL_LEVEL + LodUtil.BLOCK_DETAIL_LEVEL;
	public final static byte SECTION_CHUNK_DETAIL_LEVEL = SECTION_MINIMUM_DETAIL_LEVEL + LodUtil.CHUNK_DETAIL_LEVEL;
	public final static byte SECTION_REGION_DETAIL_LEVEL = SECTION_MINIMUM_DETAIL_LEVEL + LodUtil.REGION_DETAIL_LEVEL;
	
	
	public final byte sectionDetailLevel;
	
	/** in a sectionDetailLevel grid */
	public final int sectionX;
	/** in a sectionDetailLevel grid */
	public final int sectionZ;
	
	
	
	public DhSectionPos(byte sectionDetailLevel, int sectionX, int sectionZ)
	{
		this.sectionDetailLevel = sectionDetailLevel;
		this.sectionX = sectionX;
		this.sectionZ = sectionZ;
	}
	
	public DhSectionPos(DhBlockPos blockPos) { this(new DhBlockPos2D(blockPos)); }
	public DhSectionPos(DhBlockPos2D blockPos)
	{
		DhLodPos lodPos = new DhLodPos(LodUtil.BLOCK_DETAIL_LEVEL, blockPos.x, blockPos.z);
		lodPos = lodPos.convertToDetailLevel(SECTION_BLOCK_DETAIL_LEVEL);
		
		this.sectionDetailLevel = SECTION_BLOCK_DETAIL_LEVEL;
		this.sectionX = lodPos.x;
		this.sectionZ = lodPos.z;
	}
	
	public DhSectionPos(DhChunkPos chunkPos)
	{
		DhLodPos lodPos = new DhLodPos(LodUtil.CHUNK_DETAIL_LEVEL, chunkPos.x, chunkPos.z);
		lodPos = lodPos.convertToDetailLevel(SECTION_CHUNK_DETAIL_LEVEL);
		
		this.sectionDetailLevel = SECTION_CHUNK_DETAIL_LEVEL;
		this.sectionX = lodPos.x;
		this.sectionZ = lodPos.z;
	}
	
	public DhSectionPos(byte detailLevel, DhLodPos dhLodPos)
	{
		this.sectionDetailLevel = detailLevel;
		this.sectionX = dhLodPos.x;
		this.sectionZ = dhLodPos.z;
	}


	/** Returns the center for the highest detail level (0) */
	public DhLodPos getCenter() { return this.getCenter((byte) 0); } // TODO why does this use detail level 0 instead of this object's detail level?
	public DhLodPos getCenter(byte returnDetailLevel)
	{
		LodUtil.assertTrue(returnDetailLevel <= this.sectionDetailLevel, "returnDetailLevel must be less than sectionDetail");
		
		if (returnDetailLevel == this.sectionDetailLevel)
		{
			return new DhLodPos(this.sectionDetailLevel, this.sectionX, this.sectionZ);
		}
		
		byte detailLevelOffset = (byte) (this.sectionDetailLevel - returnDetailLevel);
		
		// we can't get the center of the position at block level, only attempt to get the position offset for detail levels above 0 // TODO should this also apply to detail level 1 or is it fine?
		int positionOffset = 0; 
		if (this.sectionDetailLevel != 1 || returnDetailLevel != 0)
		{
			positionOffset = BitShiftUtil.powerOfTwo(detailLevelOffset - 1);
		}
		
		return new DhLodPos(returnDetailLevel,
				(this.sectionX * BitShiftUtil.powerOfTwo(detailLevelOffset)) + positionOffset,
				(this.sectionZ * BitShiftUtil.powerOfTwo(detailLevelOffset)) + positionOffset);
	}
	
	/** @return the corner with the smallest X and Z coordinate */
	public DhLodPos getCorner() { return this.getCorner((byte) (this.sectionDetailLevel - 1)); }
	/** @return the corner with the smallest X and Z coordinate */
	public DhLodPos getCorner(byte returnDetailLevel)
	{
		LodUtil.assertTrue(returnDetailLevel <= this.sectionDetailLevel, "returnDetailLevel must be less than sectionDetail");
		byte offset = (byte) (this.sectionDetailLevel - returnDetailLevel);
		return new DhLodPos(returnDetailLevel,
				this.sectionX * BitShiftUtil.powerOfTwo(offset),
				this.sectionZ * BitShiftUtil.powerOfTwo(offset));
	}
	
	public DhLodUnit getWidth() { return this.getWidth(this.sectionDetailLevel);  }
	public DhLodUnit getWidth(byte returnDetailLevel)
	{
		LodUtil.assertTrue(returnDetailLevel <= this.sectionDetailLevel, "returnDetailLevel must be less than sectionDetail");
		byte offset = (byte) (this.sectionDetailLevel - returnDetailLevel);
		return new DhLodUnit(this.sectionDetailLevel, BitShiftUtil.powerOfTwo(offset));
	}
	
	/** 
	 * uses the absolute detail level aka detail levels like {@link LodUtil#CHUNK_DETAIL_LEVEL} instead of the dhSectionPos detailLevels 
	 * @return the new position closest to negative infinity with the new detail level
	 */
	public DhSectionPos convertToDetailLevel(byte newSectionDetailLevel)
	{
		DhLodPos lodPos = new DhLodPos(this.sectionDetailLevel, this.sectionX, this.sectionZ);
		lodPos = lodPos.convertToDetailLevel(newSectionDetailLevel);
		
		DhSectionPos newPos = new DhSectionPos(newSectionDetailLevel, lodPos);
		return newPos;
	}
	
	/**
	 * Returns the DhLodPos 1 detail level lower <br><br>
	 *
	 * Relative child positions returned for each index: <br>
	 * 0 = (0,0) - North West <br>
	 * 1 = (1,0) - South West <br>
	 * 2 = (0,1) - North East <br>
	 * 3 = (1,1) - South East <br>
	 *
	 * @param child0to3 must be an int between 0 and 3
	 */
	public DhSectionPos getChildByIndex(int child0to3) throws IllegalArgumentException, IllegalStateException
	{
		if (child0to3 < 0 || child0to3 > 3)
			throw new IllegalArgumentException("child0to3 must be between 0 and 3");
		if (this.sectionDetailLevel <= 0)
			throw new IllegalStateException("section detail must be greater than 0");
		
		return new DhSectionPos((byte) (this.sectionDetailLevel - 1),
				this.sectionX * 2 + (child0to3 & 1),
				this.sectionZ * 2 + BitShiftUtil.half(child0to3 & 2));
	}
	/** Returns this position's child index in its parent */
	public int getChildIndexOfParent() { return (this.sectionX & 1) + BitShiftUtil.square(this.sectionZ & 1); }
	
	/** Applies the given consumer to all 4 of this position's children. */
	public void forEachChild(Consumer<DhSectionPos> callback)
	{
		for (int i = 0; i < 4; i++)
		{
			callback.accept(this.getChildByIndex(i));
		}
	}

	/** Applies the given consumer to all children of the position at the given section detail level. */
	public void forEachChildAtLevel(byte sectionDetailLevel, Consumer<DhSectionPos> callback)
	{
		if (sectionDetailLevel == this.sectionDetailLevel)
		{
			callback.accept(this);
			return;
		}
		for (int i = 0; i < 4; i++)
		{
			this.getChildByIndex(i).forEachChildAtLevel(sectionDetailLevel, callback);
		}
	}
	
	public DhSectionPos getParentPos() { return new DhSectionPos((byte) (this.sectionDetailLevel + 1), BitShiftUtil.half(this.sectionX), BitShiftUtil.half(this.sectionZ)); }
	
	public DhSectionPos getAdjacentPos(ELodDirection dir)
	{
		return new DhSectionPos(this.sectionDetailLevel,
				this.sectionX + dir.getNormal().x,
				this.sectionZ + dir.getNormal().z);
	}
	
	public DhLodPos getSectionBBoxPos() { return new DhLodPos(this.sectionDetailLevel, this.sectionX, this.sectionZ); }
	
	/** NOTE: This does not consider yOffset! */
	public boolean overlaps(DhSectionPos other) { return this.getSectionBBoxPos().overlapsExactly(other.getSectionBBoxPos()); }
	
	/** NOTE: This does not consider yOffset! */
	public boolean contains(DhSectionPos otherPos)
	{
		DhBlockPos2D thisMinBlockPos = this.getCorner(LodUtil.BLOCK_DETAIL_LEVEL).getCornerBlockPos();
		DhBlockPos2D otherCornerBlockPos = otherPos.getCorner(LodUtil.BLOCK_DETAIL_LEVEL).getCornerBlockPos();
		
		int thisBlockWidth = this.getWidth().toBlockWidth() - 1; // minus 1 to account for zero based positional indexing
		DhBlockPos2D thisMaxBlockPos = new DhBlockPos2D(thisMinBlockPos.x + thisBlockWidth, thisMinBlockPos.z + thisBlockWidth);
		
		return thisMinBlockPos.x <= otherCornerBlockPos.x && otherCornerBlockPos.x <= thisMaxBlockPos.x &&
				thisMinBlockPos.z <= otherCornerBlockPos.z && otherCornerBlockPos.z <= thisMaxBlockPos.z;
	}
	
	/** Serialize() is different from toString() as it must NEVER be changed, and should be in a short format */
	public String serialize() { return "[" + this.sectionDetailLevel + ',' + this.sectionX + ',' + this.sectionZ + ']'; }

	@Nullable
	public static DhSectionPos deserialize(String value) {
		if (value.charAt(0) != '[' || value.charAt(value.length() - 1) != ']') return null;
		String[] split = value.substring(1, value.length() - 1).split(",");
		if (split.length != 3) return null;
		return new DhSectionPos(Byte.parseByte(split[0]), Integer.parseInt(split[1]), Integer.parseInt(split[2]));

	}
	
	@Override
	public String toString() { return "{" + this.sectionDetailLevel + "*" + this.sectionX + "," + this.sectionZ + "}"; }
	
	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
			return true;
		if (obj == null || this.getClass() != obj.getClass())
			return false;
		
		DhSectionPos that = (DhSectionPos) obj;
		return this.sectionDetailLevel == that.sectionDetailLevel &&
				this.sectionX == that.sectionX &&
				this.sectionZ == that.sectionZ;
	}
	
	@Override public int compareTo(@NotNull DhSectionPos o)
	{
		if (this.sectionDetailLevel != o.sectionDetailLevel)
			return this.sectionDetailLevel - o.sectionDetailLevel;
		if (this.sectionX != o.sectionX)
			return this.sectionX - o.sectionX;
		if (this.sectionZ != o.sectionZ)
			return this.sectionZ - o.sectionZ;
		
		return 0;
	}
	
	@Override
	public int hashCode()
	{
		return Integer.hashCode(this.sectionDetailLevel) ^ // XOR
				Integer.hashCode(this.sectionX) ^ // XOR
				Integer.hashCode(this.sectionZ);
	}
}
