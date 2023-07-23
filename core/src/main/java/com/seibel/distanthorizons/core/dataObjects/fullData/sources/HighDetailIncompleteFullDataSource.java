package com.seibel.distanthorizons.core.dataObjects.fullData.sources;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.FullDataArrayAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.SingleColumnFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IIncompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IStreamableFullDataSource;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataMetaFile;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.BitSet;

/**
 * Used for small incomplete LOD blocks.<br>
 * Handles incomplete full data with a detail level equal to or lower than 
 * {@link HighDetailIncompleteFullDataSource#MAX_SECTION_DETAIL}. <br><br>
 * 
 * Compared to other {@link IIncompleteFullDataSource}'s, this object doesn't extend {@link FullDataArrayAccessor},
 * instead it contains several "sections" of data, represented by {@link FullDataArrayAccessor}s. <br><br>
 * 
 * Formerly "SparseFullDataSource".
 * 
 * @see LowDetailIncompleteFullDataSource
 * @see CompleteFullDataSource
 * @see FullDataPointUtil
 */
public class HighDetailIncompleteFullDataSource implements IIncompleteFullDataSource, IStreamableFullDataSource<IStreamableFullDataSource.FullDataSourceSummaryData, long[][][]>
{
    private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	// TODO James would like to rename, comment, and potentially remove some of these constants. 
	//  But he doesn't currently have the understanding to do so.
    public static final byte SPARSE_UNIT_DETAIL = LodUtil.CHUNK_DETAIL_LEVEL;
    public static final byte SPARSE_UNIT_SIZE = (byte) BitShiftUtil.powerOfTwo(SPARSE_UNIT_DETAIL);
	
    public static final byte SECTION_SIZE_OFFSET = DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
    public static final int SECTION_SIZE = (byte) BitShiftUtil.powerOfTwo(SECTION_SIZE_OFFSET);
	/** aka max detail level */
    public static final byte MAX_SECTION_DETAIL = SECTION_SIZE_OFFSET + SPARSE_UNIT_DETAIL;
	
    public static final byte DATA_FORMAT_VERSION = 1;
	/** written to the binary file to mark what {@link IFullDataSource} the binary file corresponds to */
    public static final long TYPE_ID = "HighDetailIncompleteFullDataSource".hashCode();
	
	
	protected final FullDataPointIdMap mapping;
    private final DhSectionPos sectionPos;
    private final FullDataArrayAccessor[] sparseData;
    private final DhLodPos chunkPos;
	
	public final int sectionCount;
	public final int dataPointsPerSection;
    public boolean isEmpty = true;
	public EDhApiWorldGenerationStep worldGenStep = EDhApiWorldGenerationStep.EMPTY;
	private boolean isPromoted = false;
	
	
	
	//==============//
	// constructors //
	//==============//
	
    public static HighDetailIncompleteFullDataSource createEmpty(DhSectionPos pos) { return new HighDetailIncompleteFullDataSource(pos); }
    private HighDetailIncompleteFullDataSource(DhSectionPos sectionPos)
	{
        LodUtil.assertTrue(sectionPos.sectionDetailLevel > SPARSE_UNIT_DETAIL);
        LodUtil.assertTrue(sectionPos.sectionDetailLevel <= MAX_SECTION_DETAIL);
		
        this.sectionPos = sectionPos;
		this.sectionCount = BitShiftUtil.powerOfTwo(sectionPos.sectionDetailLevel - SPARSE_UNIT_DETAIL);
		this.dataPointsPerSection = SECTION_SIZE / this.sectionCount;
		
		this.sparseData = new FullDataArrayAccessor[this.sectionCount * this.sectionCount];
		this.chunkPos = sectionPos.getCorner(SPARSE_UNIT_DETAIL);
		this.mapping = new FullDataPointIdMap();
    }
	
    protected HighDetailIncompleteFullDataSource(DhSectionPos sectionPos, FullDataPointIdMap mapping, FullDataArrayAccessor[] data)
	{
        LodUtil.assertTrue(sectionPos.sectionDetailLevel > SPARSE_UNIT_DETAIL);
        LodUtil.assertTrue(sectionPos.sectionDetailLevel <= MAX_SECTION_DETAIL);
		
        this.sectionPos = sectionPos;
		this.sectionCount = 1 << (byte) (sectionPos.sectionDetailLevel - SPARSE_UNIT_DETAIL);
		this.dataPointsPerSection = SECTION_SIZE / this.sectionCount;
		
		LodUtil.assertTrue(this.sectionCount * this.sectionCount == data.length);
		this.sparseData = data;
		this.chunkPos = sectionPos.getCorner(SPARSE_UNIT_DETAIL);
		this.isEmpty = false;
        this.mapping = mapping;
    }
	
	
	
	//=================//
	// stream handling //
	//=================//
	
	
	@Override
	public void writeSourceSummaryInfo(IDhLevel level, DhDataOutputStream dataOutputStream) throws IOException
	{
		dataOutputStream.writeShort(this.getDataDetailLevel());
		dataOutputStream.writeShort(SPARSE_UNIT_DETAIL);
		dataOutputStream.writeInt(SECTION_SIZE);
		dataOutputStream.writeInt(level.getMinY());
		dataOutputStream.writeByte(this.worldGenStep.value);
		
	}
	@Override
	public FullDataSourceSummaryData readSourceSummaryInfo(FullDataMetaFile dataFile, DhDataInputStream inputStream, IDhLevel level) throws IOException
	{
		LodUtil.assertTrue(dataFile.pos.sectionDetailLevel > SPARSE_UNIT_DETAIL);
		LodUtil.assertTrue(dataFile.pos.sectionDetailLevel <= MAX_SECTION_DETAIL);
		
		int dataDetail = inputStream.readShort();
		if(dataDetail != dataFile.baseMetaData.dataLevel)
		{
			throw new IOException(LodUtil.formatLog("Data level mismatch: {} != {}", dataDetail, dataFile.baseMetaData.dataLevel));
		}
		
		// confirm that the detail level is correct
		int sparseDetail = inputStream.readShort();
		if (sparseDetail != SPARSE_UNIT_DETAIL)
		{
			throw new IOException((LodUtil.formatLog("Unexpected sparse detail level: {} != {}",
					sparseDetail, SPARSE_UNIT_DETAIL)));
		}
		
		// confirm the scale of the data points is correct
		int sectionSize = inputStream.readInt();
		if (sectionSize != SECTION_SIZE)
		{
			throw new IOException(LodUtil.formatLog(
					"Section size mismatch: {} != {} (Currently only 1 section size is supported)", sectionSize, SECTION_SIZE));
		}
		
		int minY = inputStream.readInt();
		if (minY != level.getMinY())
		{
			LOGGER.warn("Data minY mismatch: "+minY+" != "+level.getMinY()+". Will ignore data's y level");
		}
		
		EDhApiWorldGenerationStep worldGenStep = EDhApiWorldGenerationStep.fromValue(inputStream.readByte());
		if (worldGenStep == null)
		{
			worldGenStep = EDhApiWorldGenerationStep.SURFACE;
			LOGGER.warn("Missing WorldGenStep, defaulting to: "+worldGenStep.name());
		}
		
		
		return new FullDataSourceSummaryData(-1, worldGenStep);
	}
	public void setSourceSummaryData(FullDataSourceSummaryData summaryData) { this.worldGenStep = summaryData.worldGenStep; }
	
	
	@Override
	public boolean writeDataPoints(DhDataOutputStream dataOutputStream) throws IOException
	{
		if (this.isEmpty)
		{
			dataOutputStream.writeInt(IFullDataSource.NO_DATA_FLAG_BYTE);
			return false;
		}
		dataOutputStream.writeInt(IFullDataSource.DATA_GUARD_BYTE);
		
		
		// sparse array existence bitset
		BitSet dataArrayIndexHasData = new BitSet(this.sparseData.length);
		for (int i = 0; i < this.sparseData.length; i++)
		{
			dataArrayIndexHasData.set(i, this.sparseData[i] != null);
		}
		byte[] bytes = dataArrayIndexHasData.toByteArray();
		dataOutputStream.writeInt(bytes.length);
		dataOutputStream.write(bytes);
		
		
		// Data array content (only non-empty data is written)
		dataOutputStream.writeInt(IFullDataSource.DATA_GUARD_BYTE);
		for (int dataArrayIndex = dataArrayIndexHasData.nextSetBit(0);
			 dataArrayIndex >= 0;
			 dataArrayIndex = dataArrayIndexHasData.nextSetBit(dataArrayIndex+1))
		{
			// column data length
			FullDataArrayAccessor array = this.sparseData[dataArrayIndex];
			LodUtil.assertTrue(array != null);
			for (int x = 0; x < array.width(); x++)
			{
				for (int z = 0; z < array.width(); z++)
				{
					dataOutputStream.writeInt(array.get(x, z).getSingleLength());
				}
			}
			
			// column data
			for (int x = 0; x < array.width(); x++)
			{
				for (int z = 0; z < array.width(); z++)
				{
					SingleColumnFullDataAccessor column = array.get(x, z);
					LodUtil.assertTrue(column.getMapping() == this.mapping); // the mappings must be exactly equal!
					
					if (column.doesColumnExist())
					{
						long[] rawDataPoints = column.getRaw();
						for (long dataPoint : rawDataPoints)
						{
							dataOutputStream.writeLong(dataPoint);
						}
					}
				}
			}
		}
		
		
		return true;
	}
	@Override
	public long[][][] readDataPoints(FullDataMetaFile dataFile, int width, DhDataInputStream inputStream) throws IOException
	{
		// calculate the number of chunks and dataPoints based on the sparseDetail and sectionSize
		// TODO these values should be constant, should we still be calculating them like this?
		int chunks = BitShiftUtil.powerOfTwo(dataFile.pos.sectionDetailLevel - SPARSE_UNIT_DETAIL);
		int dataPointsPerChunk = SECTION_SIZE / chunks;
		
		
		// check if this file has any data
		int dataPresentFlag = inputStream.readInt();
		if (dataPresentFlag == IFullDataSource.NO_DATA_FLAG_BYTE)
		{
			// this file is empty
			return null;
		}
		else if (dataPresentFlag != IFullDataSource.DATA_GUARD_BYTE)
		{
			// the file format is incorrect
			throw new IOException("Invalid file format. Data Points guard byte expected: (no data) ["+IFullDataSource.NO_DATA_FLAG_BYTE+"] or (data present) ["+IFullDataSource.DATA_GUARD_BYTE+"], but found ["+dataPresentFlag+"].");
		}
		
		
		// get the number of columns (IE the bitSet from before)
		int numberOfDataColumns = inputStream.readInt();
		// validate the number of data columns
		int maxNumberOfDataColumns = (chunks * chunks / 8 + 64) * 2; // TODO what do these values represent?
		if (numberOfDataColumns < 0 || numberOfDataColumns > maxNumberOfDataColumns)
		{
			throw new IOException(LodUtil.formatLog("Sparse Flag BitSet size outside reasonable range: {} (expects {} to {})",
					numberOfDataColumns, 1, maxNumberOfDataColumns));
		}
		
		// read in the presence of each data column
		byte[] bytes = new byte[numberOfDataColumns];
		inputStream.readFully(bytes, 0, numberOfDataColumns);
		BitSet dataArrayIndexHasData = BitSet.valueOf(bytes);
		
		
		
		//====================//
		// Data array content //
		//====================//
		
		//  (only on non-empty columns)
		int dataArrayStartByte = inputStream.readInt();
		// confirm the column data is starting
		if (dataArrayStartByte != IFullDataSource.DATA_GUARD_BYTE)
		{
			// the file format is incorrect
			throw new IOException("invalid data length end guard");
		}
		
		
		// read in each column that has data written to it
		long[][][] rawFullDataArrays = new long[chunks * chunks][][];
		for (int fullDataIndex = dataArrayIndexHasData.nextSetBit(0);
			 fullDataIndex >= 0 && // TODO why does this happen? 
					 fullDataIndex < rawFullDataArrays.length;
			 fullDataIndex = dataArrayIndexHasData.nextSetBit(fullDataIndex + 1))
		{
			long[][] dataColumn = new long[dataPointsPerChunk * dataPointsPerChunk][];
			
			// get the column data lengths
			rawFullDataArrays[fullDataIndex] = dataColumn;
			for (int x = 0; x < dataColumn.length; x++)
			{
				// this should be zero if the column doesn't have any data
				int dataColumnLength = inputStream.readInt();
				dataColumn[x] = new long[dataColumnLength];
			}
			
			// get the column data
			for (int x = 0; x < dataColumn.length; x++)
			{
				if (dataColumn[x].length != 0)
				{
					// read in the data columns
					for (int z = 0; z < dataColumn[x].length; z++)
					{
						dataColumn[x][z] = inputStream.readLong();
					}
				}
			}
		}
		
		
		return rawFullDataArrays;
	}
	@Override
	public void setDataPoints(long[][][] dataPoints)
	{
		LodUtil.assertTrue(this.sparseData.length == dataPoints.length, "Data point array length mismatch.");
		
		this.isEmpty = false;
		
		
		for (int arrayAccessorIndex = 0; arrayAccessorIndex < dataPoints.length; arrayAccessorIndex++)
		{
			if (dataPoints[arrayAccessorIndex] == null)
			{
				this.sparseData[arrayAccessorIndex] = null;
			}
			else if (this.sparseData[arrayAccessorIndex] == null)
			{
				int width = (int) Math.sqrt(dataPoints[arrayAccessorIndex].length);
				this.sparseData[arrayAccessorIndex] = new FullDataArrayAccessor(this.mapping, dataPoints[arrayAccessorIndex], width);
			}
			else
			{
				for (int dataPointColIndex = 0; dataPointColIndex < dataPoints[arrayAccessorIndex].length; dataPointColIndex++)
				{
					System.arraycopy(dataPoints[arrayAccessorIndex][dataPointColIndex], 0, this.sparseData[arrayAccessorIndex].get(dataPointColIndex).getRaw(), 0, dataPoints[dataPointColIndex].length);
				}
			}
		}
	}
	
	
	@Override
	public void writeIdMappings(DhDataOutputStream dataOutputStream) throws IOException
	{
		dataOutputStream.writeInt(IFullDataSource.DATA_GUARD_BYTE);
		this.mapping.serialize(dataOutputStream);
		
	}
	@Override
	public FullDataPointIdMap readIdMappings(long[][][] dataPoints, DhDataInputStream inputStream) throws IOException, InterruptedException
	{
		// mark the start of the ID data
		int idMappingStartByte = inputStream.readInt();
		if (idMappingStartByte != DATA_GUARD_BYTE)
		{
			// the file format is incorrect
			throw new IOException("invalid data content end guard");
		}
		
		// deserialize the ID data
		return FullDataPointIdMap.deserialize(inputStream);
	}
	@Override
	public void setIdMapping(FullDataPointIdMap mappings) { this.mapping.mergeAndReturnRemappedEntityIds(mappings); }
	
	
	
	//======//
	// data //
	//======//
	
	public SingleColumnFullDataAccessor tryGet(int relativeX, int relativeZ)
	{
		LodUtil.assertTrue(relativeX >=0 && relativeX < SECTION_SIZE && relativeZ >=0 && relativeZ < SECTION_SIZE);
		int chunkX = relativeX / this.dataPointsPerSection;
		int chunkZ = relativeZ / this.dataPointsPerSection;
		FullDataArrayAccessor chunk = this.sparseData[chunkX * this.sectionCount + chunkZ];
		if (chunk == null)
		{
			return null;
		}
		
		return chunk.get(relativeX % this.dataPointsPerSection, relativeZ % this.dataPointsPerSection);
	}
	
	
	
	//=========//
	// getters // 
	//=========//
	
    @Override
    public DhSectionPos getSectionPos() { return this.sectionPos; }
    @Override
    public byte getDataDetailLevel() { return (byte) (this.sectionPos.sectionDetailLevel - SECTION_SIZE_OFFSET); }

    @Override
    public byte getBinaryDataFormatVersion() { return DATA_FORMAT_VERSION; }
	
	@Override 
	public EDhApiWorldGenerationStep getWorldGenStep() { return this.worldGenStep; }
	
	@Override
	public FullDataPointIdMap getMapping() { return this.mapping; }
	
	@Override
	public boolean isEmpty() { return this.isEmpty; }
	
	@Override
	public int getWidthInDataPoints() { return SECTION_SIZE; }
	
	
	private int calculateOffset(int chunkX, int chunkZ)
	{
        int offsetX = chunkX - this.chunkPos.x;
        int offsetZ = chunkZ - this.chunkPos.z;
        LodUtil.assertTrue(offsetX >= 0 && offsetZ >= 0 && offsetX < this.sectionCount && offsetZ < this.sectionCount);
        return offsetX * this.sectionCount + offsetZ;
    }
	
	
	
	//=============//
	// data update //
	//=============//
	
    @Override
    public void update(ChunkSizedFullDataAccessor chunkDataView)
	{
		int arrayOffset = this.calculateOffset(chunkDataView.pos.x, chunkDataView.pos.z);
		FullDataArrayAccessor newArray = new FullDataArrayAccessor(this.mapping, new long[this.dataPointsPerSection * this.dataPointsPerSection][], this.dataPointsPerSection);
		if (this.getDataDetailLevel() == chunkDataView.detailLevel)
		{
			chunkDataView.shadowCopyTo(newArray);
		}
		else
		{
			int count = this.dataPointsPerSection;
			int dataPerCount = SPARSE_UNIT_SIZE / this.dataPointsPerSection;
	
			for (int xOffset = 0; xOffset < count; xOffset++)
			{
				for (int zOffset = 0; zOffset < count; zOffset++)
				{
					SingleColumnFullDataAccessor column = newArray.get(xOffset, zOffset);
					column.downsampleFrom(chunkDataView.subView(dataPerCount, xOffset * dataPerCount, zOffset * dataPerCount));
				}
			}
		}
		
		this.isEmpty = false;
		this.sparseData[arrayOffset] = newArray;
    }
	
	
	// data sampling //
	
    @Override
	public void sampleFrom(IFullDataSource fullDataSource)
	{
		DhSectionPos pos = fullDataSource.getSectionPos();
		LodUtil.assertTrue(pos.sectionDetailLevel < this.sectionPos.sectionDetailLevel);
		LodUtil.assertTrue(pos.overlaps(this.sectionPos));
		if (fullDataSource.isEmpty())
		{
			return;
		}
		
		
		if (fullDataSource instanceof CompleteFullDataSource)
		{
			this.sampleFrom((CompleteFullDataSource) fullDataSource);
		}
		else if (fullDataSource instanceof HighDetailIncompleteFullDataSource)
		{
			this.sampleFrom((HighDetailIncompleteFullDataSource) fullDataSource);
		}
		else if (fullDataSource instanceof LowDetailIncompleteFullDataSource)
		{
//			this.sampleFrom((LowDetailIncompleteFullDataSource) fullDataSource);
			LodUtil.assertNotReach("SampleFrom not implemented for ["+IFullDataSource.class.getSimpleName()+"] with class ["+fullDataSource.getClass().getSimpleName()+"].");
		}
		else
		{
			LodUtil.assertNotReach("SampleFrom not implemented for ["+IFullDataSource.class.getSimpleName()+"] with class ["+fullDataSource.getClass().getSimpleName()+"].");
		}
	}
	
    private void sampleFrom(CompleteFullDataSource completeDataSource)
	{
        DhSectionPos pos = completeDataSource.getSectionPos();
		this.isEmpty = false;

        DhLodPos basePos = this.sectionPos.getCorner(SPARSE_UNIT_DETAIL);
        DhLodPos dataPos = pos.getCorner(SPARSE_UNIT_DETAIL);
        
		int coveredChunks = pos.getWidth(SPARSE_UNIT_DETAIL).numberOfLodSectionsWide;
        int sourceDataPerChunk = SPARSE_UNIT_SIZE >>> completeDataSource.getDataDetailLevel();
        LodUtil.assertTrue((coveredChunks * sourceDataPerChunk) == CompleteFullDataSource.WIDTH);
        
		int xDataOffset = dataPos.x - basePos.x;
        int zDataOffset = dataPos.z - basePos.z;
        LodUtil.assertTrue(xDataOffset >= 0 && xDataOffset < this.sectionCount && zDataOffset >= 0 && zDataOffset < this.sectionCount);
	
		for (int xOffset = 0; xOffset < coveredChunks; xOffset++)
		{
			for (int zOffset = 0; zOffset < coveredChunks; zOffset++)
			{
				FullDataArrayAccessor sourceChunk = completeDataSource.subView(sourceDataPerChunk, xOffset * sourceDataPerChunk, zOffset * sourceDataPerChunk);
				FullDataArrayAccessor newFullDataAccessor = new FullDataArrayAccessor(this.mapping, new long[this.dataPointsPerSection * this.dataPointsPerSection][], this.dataPointsPerSection);
				newFullDataAccessor.downsampleFrom(sourceChunk);
				this.sparseData[(xOffset + xDataOffset) * this.sectionCount + (zOffset + zDataOffset)] = newFullDataAccessor;
			}
		}
    }
	private void sampleFrom(HighDetailIncompleteFullDataSource sparseDataSource)
	{
		DhSectionPos pos = sparseDataSource.getSectionPos();
		this.isEmpty = false;
		
		DhLodPos basePos = this.sectionPos.getCorner(SPARSE_UNIT_DETAIL);
		DhLodPos dataPos = pos.getCorner(SPARSE_UNIT_DETAIL);
		
		int offsetX = dataPos.x-basePos.x;
		int offsetZ = dataPos.z-basePos.z;
		LodUtil.assertTrue(offsetX >= 0 && offsetX < this.sectionCount && offsetZ >= 0 && offsetZ < this.sectionCount);
		
		for (int xOffset = 0; xOffset < sparseDataSource.sectionCount; xOffset++)
		{
			for (int zOffset = 0; zOffset < sparseDataSource.sectionCount; zOffset++)
			{
				FullDataArrayAccessor sourceChunk = sparseDataSource.sparseData[xOffset * sparseDataSource.sectionCount + zOffset];
				if (sourceChunk != null)
				{
					FullDataArrayAccessor newFullDataAccessor = new FullDataArrayAccessor(this.mapping, new long[this.dataPointsPerSection * this.dataPointsPerSection][], this.dataPointsPerSection);
					newFullDataAccessor.downsampleFrom(sourceChunk);
					this.sparseData[(xOffset + offsetX) * this.sectionCount + (zOffset + offsetZ)] = newFullDataAccessor;
				}
			}
		}
	}
	private void sampleFrom(LowDetailIncompleteFullDataSource spottyDataSource)
	{
		// TODO implement
		
//		DhSectionPos pos = spottyDataSource.getSectionPos();
//		this.isEmpty = false;
//		
//		DhLodPos basePos = this.sectionPos.getCorner(SPARSE_UNIT_DETAIL);
//		DhLodPos dataPos = pos.getCorner(SPARSE_UNIT_DETAIL);
//		
//		int coveredChunks = pos.getWidth(SPARSE_UNIT_DETAIL).numberOfLodSectionsWide;
//		int sourceDataPerChunk = SPARSE_UNIT_SIZE >>> spottyDataSource.getDataDetailLevel();
//		LodUtil.assertTrue((coveredChunks * sourceDataPerChunk) == CompleteFullDataSource.WIDTH);
//		
//		int xDataOffset = dataPos.x - basePos.x;
//		int zDataOffset = dataPos.z - basePos.z;
//		LodUtil.assertTrue(xDataOffset >= 0 && xDataOffset < this.sectionCount && zDataOffset >= 0 && zDataOffset < this.sectionCount);
//		
//		for (int xOffset = 0; xOffset < coveredChunks; xOffset++)
//		{
//			for (int zOffset = 0; zOffset < coveredChunks; zOffset++)
//			{
//				FullDataArrayAccessor sourceChunk = spottyDataSource.subView(sourceDataPerChunk, xOffset * sourceDataPerChunk, zOffset * sourceDataPerChunk);
//				FullDataArrayAccessor newFullDataAccessor = new FullDataArrayAccessor(this.mapping, new long[this.dataPointsPerSection * this.dataPointsPerSection][], this.dataPointsPerSection);
//				newFullDataAccessor.downsampleFrom(sourceChunk);
//				this.sparseData[(xOffset + xDataOffset) * this.sectionCount + (zOffset + zDataOffset)] = newFullDataAccessor;
//			}
//		}
	}
	
	
    private void applyToFullDataSource(CompleteFullDataSource dataSource)
	{
        LodUtil.assertTrue(dataSource.getSectionPos().equals(this.sectionPos));
        LodUtil.assertTrue(dataSource.getDataDetailLevel() == this.getDataDetailLevel());
		for (int x = 0; x < this.sectionCount; x++)
		{
			for (int z = 0; z < this.sectionCount; z++)
			{
				FullDataArrayAccessor array = this.sparseData[x * this.sectionCount + z];
				if (array == null)
					continue;
				
				// Otherwise, apply data to dataSource
				dataSource.markNotEmpty();
				FullDataArrayAccessor view = dataSource.subView(this.dataPointsPerSection, x * this.dataPointsPerSection, z * this.dataPointsPerSection);
				array.shadowCopyTo(view);
			}
		}
    }

    public IFullDataSource tryPromotingToCompleteDataSource()
	{
        if (this.isEmpty)
		{
			return this;
		}
		
		// promotion can only succeed if every data column is present
        for (FullDataArrayAccessor array : this.sparseData)
		{
			if (array == null)
			{
				return this;
			}
		}
		isPromoted = true;
        CompleteFullDataSource fullDataSource = CompleteFullDataSource.createEmpty(this.sectionPos);
		this.applyToFullDataSource(fullDataSource);
        return fullDataSource;
    }

	@Override
	public boolean hasBeenPromoted() {
		return isPromoted;
	}

}
