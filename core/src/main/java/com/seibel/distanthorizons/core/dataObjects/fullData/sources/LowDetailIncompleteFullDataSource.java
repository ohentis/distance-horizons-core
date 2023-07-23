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
 * Used for large incomplete LOD blocks. <Br>
 * Handles incomplete full data with a detail level higher than 
 * {@link HighDetailIncompleteFullDataSource#MAX_SECTION_DETAIL}. <br><br>
 *  
 * Formerly "SpottyFullDataSource".
 * 
 * @see HighDetailIncompleteFullDataSource
 * @see CompleteFullDataSource
 * @see FullDataPointUtil
 */
public class LowDetailIncompleteFullDataSource extends FullDataArrayAccessor implements IIncompleteFullDataSource, IStreamableFullDataSource<IStreamableFullDataSource.FullDataSourceSummaryData, LowDetailIncompleteFullDataSource.StreamDataPointContainer>
{
    private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
    public static final byte SECTION_SIZE_OFFSET = DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
	/** measured in dataPoints */
    public static final int WIDTH = BitShiftUtil.powerOfTwo(SECTION_SIZE_OFFSET);
	
    public static final byte DATA_FORMAT_VERSION = 1;
	/** written to the binary file to mark what {@link IFullDataSource} the binary file corresponds to */
    public static final long TYPE_ID = "LowDetailIncompleteFullDataSource".hashCode();
	
	
    private final DhSectionPos sectionPos;
	private final BitSet isColumnNotEmpty;
	
    private boolean isEmpty = true;
	public EDhApiWorldGenerationStep worldGenStep = EDhApiWorldGenerationStep.EMPTY;
	private boolean isPromoted = false;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public static LowDetailIncompleteFullDataSource createEmpty(DhSectionPos pos) { return new LowDetailIncompleteFullDataSource(pos); }
    private LowDetailIncompleteFullDataSource(DhSectionPos sectionPos)
	{
        super(new FullDataPointIdMap(), new long[WIDTH * WIDTH][0], WIDTH);
        LodUtil.assertTrue(sectionPos.sectionDetailLevel > HighDetailIncompleteFullDataSource.MAX_SECTION_DETAIL);
		
        this.sectionPos = sectionPos;
		this.isColumnNotEmpty = new BitSet(WIDTH * WIDTH);
		this.worldGenStep = EDhApiWorldGenerationStep.EMPTY;
    }
	
	private LowDetailIncompleteFullDataSource(DhSectionPos pos, FullDataPointIdMap mapping, EDhApiWorldGenerationStep worldGenStep, BitSet isColumnNotEmpty, long[][] data)
	{
		super(mapping, data, WIDTH);
		LodUtil.assertTrue(data.length == WIDTH * WIDTH);
		
		this.sectionPos = pos;
		this.isColumnNotEmpty = isColumnNotEmpty;
		this.worldGenStep = worldGenStep;
		this.isEmpty = false;
	}
	
	
	
	//=================//
	// stream handling //
	//=================//
	
	
	@Override
	public void writeSourceSummaryInfo(IDhLevel level, DhDataOutputStream outputStream) throws IOException
	{
		outputStream.writeInt(this.getDataDetailLevel());
		outputStream.writeInt(this.width);
		outputStream.writeInt(level.getMinY());
		outputStream.writeByte(this.worldGenStep.value);
		
	}
	@Override
	public FullDataSourceSummaryData readSourceSummaryInfo(FullDataMetaFile dataFile, DhDataInputStream inputStream, IDhLevel level) throws IOException
	{
		int dataDetail = inputStream.readInt();
		if(dataDetail != dataFile.baseMetaData.dataLevel)
		{
			throw new IOException(LodUtil.formatLog("Data level mismatch: "+dataDetail+" != "+dataFile.baseMetaData.dataLevel));
		}
		
		int width = inputStream.readInt();
		if (width != WIDTH)
		{
			throw new IOException(LodUtil.formatLog("Section size mismatch: "+width+" != "+ WIDTH +" (Currently only 1 section size is supported)"));
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
		
		
		return new FullDataSourceSummaryData(this.width, worldGenStep);
	}
	public void setSourceSummaryData(FullDataSourceSummaryData summaryData)
	{
		this.worldGenStep = summaryData.worldGenStep;
	}
	
	
	@Override
	public boolean writeDataPoints(DhDataOutputStream dataOutputStream) throws IOException
	{
		if (this.isEmpty)
		{
			dataOutputStream.writeInt(IFullDataSource.NO_DATA_FLAG_BYTE);
			return false;
		}
		dataOutputStream.writeInt(IFullDataSource.DATA_GUARD_BYTE);
		
		
		// data column presence
		byte[] bytes = this.isColumnNotEmpty.toByteArray();
		dataOutputStream.writeInt(bytes.length);
		dataOutputStream.write(bytes);
		
		
		// Data content
		dataOutputStream.writeInt(IFullDataSource.DATA_GUARD_BYTE);
		for (int i = this.isColumnNotEmpty.nextSetBit(0); i >= 0; i = this.isColumnNotEmpty.nextSetBit(i + 1))
		{
			dataOutputStream.writeByte(this.dataArrays[i].length);
			for (long dataPoint : this.dataArrays[i])
			{
				dataOutputStream.writeLong(dataPoint);
			}
		}
		
		
		return true;
	}
	@Override
	public StreamDataPointContainer readDataPoints(FullDataMetaFile dataFile, int width, DhDataInputStream inputStream) throws IOException
	{
		// is source empty flag
		int dataPresentFlag = inputStream.readInt();
		if (dataPresentFlag == IFullDataSource.NO_DATA_FLAG_BYTE)
		{
			// Section is empty
			return null;
		}
		else if (dataPresentFlag != IFullDataSource.DATA_GUARD_BYTE)
		{
			throw new IOException("Invalid file format. Data Points guard byte expected: (no data) ["+IFullDataSource.NO_DATA_FLAG_BYTE+"] or (data present) ["+IFullDataSource.DATA_GUARD_BYTE+"], but found ["+dataPresentFlag+"].");
		}
		
		
		// data column presence
		int length = inputStream.readInt();
		if (length < 0 || length > (WIDTH * WIDTH /8+64)*2) // TODO replace magic numbers or comment what they mean
		{
			throw new IOException(LodUtil.formatLog("Spotty Flag BitSet size outside reasonable range: {} (expects {} to {})",
					length, 1, WIDTH * WIDTH / 8 + 63));
		}
		
		byte[] bytes = new byte[length];
		inputStream.readFully(bytes, 0, length);
		BitSet isColumnNotEmpty = BitSet.valueOf(bytes);
		
		
		
		// Data array content
		long[][] dataPointArray = new long[WIDTH * WIDTH][];
		dataPresentFlag = inputStream.readInt();
		if (dataPresentFlag != IFullDataSource.DATA_GUARD_BYTE)
		{
			throw new IOException("invalid spotty flag end guard");
		}
		
		for (int xz = isColumnNotEmpty.nextSetBit(0); xz >= 0; xz = isColumnNotEmpty.nextSetBit(xz + 1))
		{
			long[] array = new long[inputStream.readByte()];
			for (int y = 0; y < array.length; y++)
			{
				array[y] = inputStream.readLong();
			}
			dataPointArray[xz] = array;
		}
		
		
		return new StreamDataPointContainer(dataPointArray, isColumnNotEmpty);
	}
	@Override
	public void setDataPoints(StreamDataPointContainer streamDataPointContainer)
	{
		long[][] dataPoints = streamDataPointContainer.dataPoints;
		
		// copy over the datapoints
		LodUtil.assertTrue(this.dataArrays.length == dataPoints.length, "Data point array length mismatch.");
		System.arraycopy(dataPoints, 0, this.dataArrays, 0, dataPoints.length);
		
		// overwrite the bitset
		for (int i = 0; i < streamDataPointContainer.isColumnNotEmpty.length(); i++)
		{
			this.isColumnNotEmpty.set(i, streamDataPointContainer.isColumnNotEmpty.get(i));
		}
		
		this.isEmpty = false;
	}
	
	
	@Override
	public void writeIdMappings(DhDataOutputStream outputStream) throws IOException
	{
		outputStream.writeInt(IFullDataSource.DATA_GUARD_BYTE);
		this.mapping.serialize(outputStream);
		
	}
	@Override
	public FullDataPointIdMap readIdMappings(StreamDataPointContainer streamDataPointContainer, DhDataInputStream inputStream) throws IOException, InterruptedException
	{
		// Id mapping
		int dataPresentFlag = inputStream.readInt();
		if (dataPresentFlag != IFullDataSource.DATA_GUARD_BYTE)
		{
			throw new IOException("invalid ID mapping end guard");
		}
		return FullDataPointIdMap.deserialize(inputStream);
	}
	@Override
	public void setIdMapping(FullDataPointIdMap mappings) { this.mapping.mergeAndReturnRemappedEntityIds(mappings); }
	
	
	
	//======//
	// data //
	//======//
	
    @Override
    public SingleColumnFullDataAccessor tryGet(int relativeX, int relativeZ) { return this.isColumnNotEmpty.get(relativeX * WIDTH + relativeZ) ? this.get(relativeX, relativeZ) : null; }
	
	
	
	//=====================//
	// getters and setters //
	//=====================//
	
	@Override
	public DhSectionPos getSectionPos() { return this.sectionPos; }
	@Override
	public byte getDataDetailLevel() { return (byte) (this.sectionPos.sectionDetailLevel - SECTION_SIZE_OFFSET); }
	@Override
	public byte getBinaryDataFormatVersion() { return DATA_FORMAT_VERSION;  }
	
	@Override
	public EDhApiWorldGenerationStep getWorldGenStep() { return this.worldGenStep; }
	
	@Override
	public boolean isEmpty() { return this.isEmpty; }
	public void markNotEmpty() { this.isEmpty = false;  }
	
	@Override
	public int getWidthInDataPoints() { return WIDTH; }
	
	
	
	//===============//
	// Data updating //
	//===============//
	
	@Override
	public void update(ChunkSizedFullDataAccessor data)
	{
		LodUtil.assertTrue(this.sectionPos.getSectionBBoxPos().overlapsExactly(data.getLodPos()));
		
		if (this.getDataDetailLevel() >= 4)
		{
			//FIXME: TEMPORARY
			int chunkPerFull = 1 << (this.getDataDetailLevel() - 4);
			if (data.pos.x % chunkPerFull != 0 || data.pos.z % chunkPerFull != 0)
			{
				return;
			}
			
			DhLodPos baseOffset = this.sectionPos.getCorner(this.getDataDetailLevel());
			DhLodPos dataOffset = data.getLodPos().convertToDetailLevel(this.getDataDetailLevel());
			int offsetX = dataOffset.x - baseOffset.x;
			int offsetZ = dataOffset.z - baseOffset.z;
			LodUtil.assertTrue(offsetX >= 0 && offsetX < WIDTH && offsetZ >= 0 && offsetZ < WIDTH);
			this.isEmpty = false;
			
			SingleColumnFullDataAccessor columnFullDataAccessor = this.get(offsetX, offsetZ);
			data.get(0,0).deepCopyTo(columnFullDataAccessor);

			this.isColumnNotEmpty.set(offsetX * WIDTH + offsetZ, columnFullDataAccessor.doesColumnExist());
		}
		else
		{
			LodUtil.assertNotReach();
			//TODO;
		}
		
	}
	
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
			this.sampleFrom((LowDetailIncompleteFullDataSource) fullDataSource);
//			LodUtil.assertNotReach("SampleFrom not implemented for ["+IFullDataSource.class.getSimpleName()+"] with class ["+fullDataSource.getClass().getSimpleName()+"].");
		}
		else
		{
			// TODO implement
			LodUtil.assertNotReach("SampleFrom not implemented for ["+this.getClass().getSimpleName()+"] with class ["+fullDataSource.getClass().getSimpleName()+"].");
		}
	}
	
	private void sampleFrom(HighDetailIncompleteFullDataSource sparseSource)
	{
		DhLodPos thisLodPos = this.sectionPos.getCorner(this.getDataDetailLevel());
		DhSectionPos pos = sparseSource.getSectionPos();
		
		this.isEmpty = false;
		
		if (this.getDataDetailLevel() > this.sectionPos.sectionDetailLevel)
		{
			DhLodPos dataLodPos = pos.getCorner(this.getDataDetailLevel());
			
			int offsetX = dataLodPos.x - thisLodPos.x;
			int offsetZ = dataLodPos.z - thisLodPos.z;
			LodUtil.assertTrue(offsetX >= 0 && offsetX < WIDTH && offsetZ >= 0 && offsetZ < WIDTH);
			
			int chunksPerData = 1 << (this.getDataDetailLevel() - HighDetailIncompleteFullDataSource.SPARSE_UNIT_DETAIL);
			int dataSpan = this.sectionPos.getWidth(this.getDataDetailLevel()).numberOfLodSectionsWide;
			
			for (int xOffset = 0; xOffset < dataSpan; xOffset++)
			{
				for (int zOffset = 0; zOffset < dataSpan; zOffset++)
				{
					SingleColumnFullDataAccessor column = sparseSource.tryGet(
							xOffset * chunksPerData * sparseSource.dataPointsPerSection,
							zOffset * chunksPerData * sparseSource.dataPointsPerSection);
					
					if (column != null)
					{
						column.deepCopyTo(this.get(offsetX + xOffset, offsetZ + zOffset));
						this.isColumnNotEmpty.set((offsetX + xOffset) * WIDTH + offsetZ + zOffset, true);
					}
				}
			}
		}
		else
		{
			DhLodPos dataLodPos = pos.getSectionBBoxPos();
			int lowerSectionsPerData = this.sectionPos.getWidth(dataLodPos.detailLevel).numberOfLodSectionsWide;
			if (dataLodPos.x % lowerSectionsPerData != 0 || dataLodPos.z % lowerSectionsPerData != 0)
			{
				return;
			}
			
			
			dataLodPos = dataLodPos.convertToDetailLevel(this.getDataDetailLevel());
			int offsetX = dataLodPos.x - thisLodPos.x;
			int offsetZ = dataLodPos.z - thisLodPos.z;
			
			SingleColumnFullDataAccessor column = sparseSource.tryGet(0, 0);
			if (column != null)
			{
				column.deepCopyTo(this.get(offsetX, offsetZ));
				this.isColumnNotEmpty.set(offsetX * WIDTH + offsetZ, true);
			}
		}
	}
	
	private void sampleFrom(CompleteFullDataSource completeSource)
	{
		DhSectionPos pos = completeSource.getSectionPos();
		this.isEmpty = false;
		this.downsampleFrom(completeSource);
		
		if (this.getDataDetailLevel() > this.sectionPos.sectionDetailLevel) // TODO what does this mean?
		{
			DhLodPos thisLodPos = this.sectionPos.getCorner(this.getDataDetailLevel());
			DhLodPos dataLodPos = pos.getCorner(this.getDataDetailLevel());
			
			int offsetX = dataLodPos.x - thisLodPos.x;
			int offsetZ = dataLodPos.z - thisLodPos.z;
			int dataWidth = this.sectionPos.getWidth(this.getDataDetailLevel()).numberOfLodSectionsWide;
			
			for (int xOffset = 0; xOffset < dataWidth; xOffset++)
			{
				for (int zOffset = 0; zOffset < dataWidth; zOffset++)
				{
					this.isColumnNotEmpty.set((offsetX + xOffset) * WIDTH + offsetZ + zOffset, true);
				}
			}
		}
		else
		{
			DhLodPos dataPos = pos.getSectionBBoxPos();
			int lowerSectionsPerData = this.sectionPos.getWidth(dataPos.detailLevel).numberOfLodSectionsWide;
			if (dataPos.x % lowerSectionsPerData != 0 || dataPos.z % lowerSectionsPerData != 0)
			{
				return;
			}
			
			
			DhLodPos basePos = this.sectionPos.getCorner(this.getDataDetailLevel());
			dataPos = dataPos.convertToDetailLevel(this.getDataDetailLevel());
			int offsetX = dataPos.x - basePos.x;
			int offsetZ = dataPos.z - basePos.z;
			this.isColumnNotEmpty.set(offsetX * WIDTH + offsetZ, true);
		}
		
	}
	
	private void sampleFrom(LowDetailIncompleteFullDataSource spottySource)
	{
		DhSectionPos pos = spottySource.getSectionPos();
		this.isEmpty = false;
		this.downsampleFrom(spottySource);
		
		
		if (this.getDataDetailLevel() > this.sectionPos.sectionDetailLevel)
		{
			DhLodPos thisLodPos = this.sectionPos.getCorner(this.getDataDetailLevel());
			DhLodPos dataLodPos = pos.getCorner(this.getDataDetailLevel());
			
			int offsetX = dataLodPos.x - thisLodPos.x;
			int offsetZ = dataLodPos.z - thisLodPos.z;
			int dataWidth = this.sectionPos.getWidth(this.getDataDetailLevel()).numberOfLodSectionsWide;
			
			for (int xOffset = 0; xOffset < dataWidth; xOffset++)
			{
				for (int zOffset = 0; zOffset < dataWidth; zOffset++)
				{
					this.isColumnNotEmpty.set((offsetX + xOffset) * WIDTH + offsetZ + zOffset, true);
				}
			}
		}
		else
		{
			DhLodPos dataPos = pos.getSectionBBoxPos();
			int lowerSectionsPerData = this.sectionPos.getWidth(dataPos.detailLevel).numberOfLodSectionsWide;
			if (dataPos.x % lowerSectionsPerData != 0 || dataPos.z % lowerSectionsPerData != 0)
			{
				return;
			}
			
			
			DhLodPos basePos = this.sectionPos.getCorner(this.getDataDetailLevel());
			dataPos = dataPos.convertToDetailLevel(this.getDataDetailLevel());
			int offsetX = dataPos.x - basePos.x;
			int offsetZ = dataPos.z - basePos.z;
			this.isColumnNotEmpty.set(offsetX * WIDTH + offsetZ, true);
		}
	}
	
	@Override
	public IFullDataSource tryPromotingToCompleteDataSource()
	{
		// promotion can only be completed if every column has data
		if (this.isEmpty)
		{
			return this;
		}
		else if (this.isColumnNotEmpty.cardinality() != WIDTH * WIDTH)
		{
			return this;
		}
		isPromoted = true;
		return new CompleteFullDataSource(this.sectionPos, this.mapping, this.dataArrays);
	}

	@Override
	public boolean hasBeenPromoted() {
		return isPromoted;
	}


	//================//
	// helper classes //
	//================//
	
	/** used when reading the datapoints to and from the {@link IStreamableFullDataSource} */
	public static class StreamDataPointContainer
	{
		public long[][] dataPoints;
		public BitSet isColumnNotEmpty;
		
		public StreamDataPointContainer(long[][] dataPoints, BitSet isColumnNotEmpty)
		{
			this.dataPoints = dataPoints;
			this.isColumnNotEmpty = isColumnNotEmpty;
		}
	}
	
	
	
	//========//
	// unused //
	//========//
	
	public static boolean neededForPosition(DhSectionPos posToWrite, DhSectionPos posToTest)
	{
		if (!posToWrite.overlaps(posToTest))
			return false;
		if (posToTest.sectionDetailLevel > posToWrite.sectionDetailLevel)
			return false;
		if (posToWrite.sectionDetailLevel - posToTest.sectionDetailLevel <= SECTION_SIZE_OFFSET)
			return true;
		byte sectPerData = (byte) (1 << (posToWrite.sectionDetailLevel - posToTest.sectionDetailLevel - SECTION_SIZE_OFFSET));
		return posToTest.sectionX % sectPerData == 0 && posToTest.sectionZ % sectPerData == 0;
	}
	
	
}
