package com.seibel.distanthorizons.core.dataObjects.fullData.accessor;

import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IStreamableFullDataSource;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * A more specific version of {@link FullDataArrayAccessor}
 * that only contains full data for a single chunk.
 *
 * @see FullDataPointUtil
 */
public class ChunkSizedFullDataAccessor extends FullDataArrayAccessor
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
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
	
	
	//=================//
	// stream handling //
	//=================//
	
	
	public void writeSourceSummaryInfo(IDhLevel level, DhDataOutputStream outputStream) throws IOException
	{
		outputStream.writeInt(level.getMinY());
	}
	
	public void readSourceSummaryInfo(DhDataInputStream inputStream, IDhLevel level) throws IOException
	{
		int minY = inputStream.readInt();
		if (minY != level.getMinY())
		{
			LOGGER.warn("Data minY mismatch: " + minY + " != " + level.getMinY() + ". Will ignore data's y level");
		}
	}
	
	public boolean writeDataPoints(DhDataOutputStream outputStream) throws IOException
	{
		outputStream.writeInt(IFullDataSource.DATA_GUARD_BYTE);
		
		
		
		// Data array length
		for (int x = 0; x < this.width; x++)
		{
			for (int z = 0; z < this.width; z++)
			{
				outputStream.writeInt(this.get(x, z).getSingleLength());
			}
		}
		
		
		
		// Data array content (only on non-empty columns)
		outputStream.writeInt(IFullDataSource.DATA_GUARD_BYTE);
		for (int x = 0; x < this.width; x++)
		{
			for (int z = 0; z < this.width; z++)
			{
				SingleColumnFullDataAccessor columnAccessor = this.get(x, z);
				if (columnAccessor.doesColumnExist())
				{
					long[] dataPointArray = columnAccessor.getRaw();
					for (long dataPoint : dataPointArray)
					{
						outputStream.writeLong(dataPoint);
					}
				}
			}
		}
		
		
		return true;
	}
	public long[][] readDataPoints(DhDataInputStream dataInputStream) throws IOException
	{
		// Data array length
		int dataPresentFlag = dataInputStream.readInt();
		if (dataPresentFlag != IFullDataSource.DATA_GUARD_BYTE)
		{
			throw new IOException("Invalid file format. Data Points guard byte expected: (no data) [" + IFullDataSource.NO_DATA_FLAG_BYTE + "] or (data present) [" + IFullDataSource.DATA_GUARD_BYTE + "], but found [" + dataPresentFlag + "].");
		}
		
		
		
		long[][] dataPointArray = new long[width * width][];
		for (int x = 0; x < width; x++)
		{
			for (int z = 0; z < width; z++)
			{
				dataPointArray[x * width + z] = new long[dataInputStream.readInt()];
			}
		}
		
		
		
		// check if the array start flag is present
		int arrayStartFlag = dataInputStream.readInt();
		if (arrayStartFlag != IFullDataSource.DATA_GUARD_BYTE)
		{
			throw new IOException("invalid data length end guard");
		}
		
		for (int xz = 0; xz < dataPointArray.length; xz++) // x and z are combined
		{
			if (dataPointArray[xz].length != 0)
			{
				for (int y = 0; y < dataPointArray[xz].length; y++)
				{
					dataPointArray[xz][y] = dataInputStream.readLong();
				}
			}
		}
		
		
		
		return dataPointArray;
	}
	public void setDataPoints(long[][] dataPoints)
	{
		LodUtil.assertTrue(this.dataArrays.length == dataPoints.length, "Data point array length mismatch.");
		
		System.arraycopy(dataPoints, 0, this.dataArrays, 0, dataPoints.length);
	}
	
	
	public void writeIdMappings(DhDataOutputStream outputStream, ILevelWrapper levelWrapper) throws IOException
	{
		outputStream.writeInt(IFullDataSource.DATA_GUARD_BYTE);
		this.mapping.serialize(outputStream, levelWrapper);
	}
	public FullDataPointIdMap readIdMappings(DhDataInputStream inputStream, ILevelWrapper levelWrapper) throws IOException, InterruptedException
	{
		int guardByte = inputStream.readInt();
		if (guardByte != IFullDataSource.DATA_GUARD_BYTE)
		{
			throw new IOException("Invalid data content end guard for ID mapping");
		}
		
		return FullDataPointIdMap.deserialize(inputStream, null, levelWrapper);
	}
	public void setIdMapping(FullDataPointIdMap mappings) { this.mapping.mergeAndReturnRemappedEntityIds(mappings); }
	
	
	public void populateFromStream(DhDataInputStream inputStream, IDhLevel level) throws IOException, InterruptedException
	{
		this.readSourceSummaryInfo(inputStream, level);
		
		long[][] dataPoints = this.readDataPoints(inputStream);
		if (dataPoints == null)
		{
			return;
		}
		this.setDataPoints(dataPoints);
		
		
		FullDataPointIdMap mapping = this.readIdMappings(inputStream, level.getLevelWrapper());
		this.setIdMapping(mapping);
		
	}
	
	public void writeToStream(DhDataOutputStream outputStream, IDhLevel level) throws IOException
	{
		this.writeSourceSummaryInfo(level, outputStream);
		
		boolean hasData = this.writeDataPoints(outputStream);
		if (!hasData)
		{
			return;
		}
		
		this.writeIdMappings(outputStream, level.getLevelWrapper());
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