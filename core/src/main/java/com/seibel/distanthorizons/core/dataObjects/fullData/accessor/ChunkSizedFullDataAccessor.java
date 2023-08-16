package com.seibel.distanthorizons.core.dataObjects.fullData.accessor;

import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.LodUtil;

/**
 * A more specific version of {@link FullDataArrayAccessor}
 * that only contains full data for a single chunk.
 *
 * @see FullDataPointUtil
 */
public class ChunkSizedFullDataAccessor extends FullDataArrayAccessor
{
	public final DhChunkPos pos;
	// TODO replace this var with LodUtil.BLOCK_DETAIL_LEVEL 
	public final byte detailLevel = LodUtil.BLOCK_DETAIL_LEVEL;
	
	
	
	public ChunkSizedFullDataAccessor(DhChunkPos pos)
	{
		super(new FullDataPointIdMap(new DhSectionPos(pos)),
				new long[LodUtil.CHUNK_WIDTH * LodUtil.CHUNK_WIDTH][0],
				LodUtil.CHUNK_WIDTH);
		
		this.pos = pos;
	}
	
	
	
	public void setSingleColumn(long[] data, int xRelative, int zRelative) { this.dataArrays[xRelative * LodUtil.CHUNK_WIDTH + zRelative] = data; }
	
	public long nonEmptyCount()
	{
		long count = 0;
		for (long[] data : this.dataArrays)
		{
			if (data.length != 0)
			{
				count += 1;
			}
		}
		return count;
	}
	
	public long emptyCount() { return (LodUtil.CHUNK_WIDTH * LodUtil.CHUNK_WIDTH) - this.nonEmptyCount(); }
	
	public DhLodPos getLodPos() { return new DhLodPos(LodUtil.CHUNK_DETAIL_LEVEL, this.pos.x, this.pos.z); }
	
	@Override
	public String toString() { return this.pos + " " + this.nonEmptyCount(); }
	
}