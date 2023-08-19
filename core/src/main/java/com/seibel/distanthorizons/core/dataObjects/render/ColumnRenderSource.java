package com.seibel.distanthorizons.core.dataObjects.render;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.dataObjects.transformers.FullDataToRenderDataTransformer;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.ColumnArrayView;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.ColumnQuadView;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.IColumnDataView;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import com.seibel.distanthorizons.core.util.ColorUtil;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stores the render data used to generate OpenGL buffers.
 *
 * @see    RenderDataPointUtil
 */
public class ColumnRenderSource
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	public static final boolean DO_SAFETY_CHECKS = ModInfo.IS_DEV_BUILD;
	public static final byte SECTION_SIZE_OFFSET = DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
	public static final int SECTION_SIZE = BitShiftUtil.powerOfTwo(SECTION_SIZE_OFFSET);
	
	public static final byte DATA_FORMAT_VERSION = 1;
	public static final long TYPE_ID = "ColumnRenderSource".hashCode();
	
	/**
	 * This is the byte put between different sections in the binary save file.
	 * The presence and absence of this byte indicates if the file is correctly formatted.
	 */
	public static final int DATA_GUARD_BYTE = 0xFFFFFFFF;
	/** indicates the binary save file represents an empty data source */
	public static final int NO_DATA_FLAG_BYTE = 0x00000001;
	
	
	
	public int verticalDataCount;
	public final DhSectionPos sectionPos;
	public final int yOffset;
	
	public long[] renderDataContainer;
	
	public final DebugSourceFlag[] debugSourceFlags;
	
	private boolean isEmpty = true;
	public EDhApiWorldGenerationStep worldGenStep;
	
	public AtomicLong localVersion = new AtomicLong(0); // used to track changes to the data source, so that buffers can be updated when necessary
	
	//==============//
	// constructors //
	//==============//
	
	public static ColumnRenderSource createEmptyRenderSource(DhSectionPos sectionPos) { return new ColumnRenderSource(sectionPos, 0, 0); }
	/**
	 * Creates an empty ColumnRenderSource.
	 *
	 * @param sectionPos the relative position of the container
	 * @param maxVerticalSize the maximum vertical size of the container
	 */
	public ColumnRenderSource(DhSectionPos sectionPos, int maxVerticalSize, int yOffset)
	{
		this.verticalDataCount = maxVerticalSize;
		this.renderDataContainer = new long[SECTION_SIZE * SECTION_SIZE * this.verticalDataCount];
		this.debugSourceFlags = new DebugSourceFlag[SECTION_SIZE * SECTION_SIZE];
		this.sectionPos = sectionPos;
		this.yOffset = yOffset;
		this.worldGenStep = EDhApiWorldGenerationStep.EMPTY;
	}
	
	/**
	 * Creates a new ColumnRenderSource from the parsedColumnData.
	 *
	 * @throws IOException if the DataInputStream's detail level isn't what was expected
	 */
	public ColumnRenderSource(DhSectionPos sectionPos, ColumnRenderLoader.ParsedColumnData parsedColumnData, IDhLevel level) throws IOException
	{
		if (sectionPos.sectionDetailLevel - SECTION_SIZE_OFFSET != parsedColumnData.detailLevel)
		{
			throw new IOException("Invalid data: detail level does not match");
		}
		
		this.sectionPos = sectionPos;
		this.yOffset = level.getMinY();
		this.verticalDataCount = parsedColumnData.verticalSize;
		this.renderDataContainer = parsedColumnData.dataContainer;
		this.worldGenStep = parsedColumnData.worldGenStep;
		this.isEmpty = parsedColumnData.isEmpty;
		
		this.debugSourceFlags = new DebugSourceFlag[SECTION_SIZE * SECTION_SIZE];
		this.fillDebugFlag(0, 0, SECTION_SIZE, SECTION_SIZE, DebugSourceFlag.FILE);
	}
	
	
	
	//========================//
	// datapoint manipulation //
	//========================//
	
	public void clearDataPoint(int posX, int posZ)
	{
		for (int verticalIndex = 0; verticalIndex < this.verticalDataCount; verticalIndex++)
		{
			this.renderDataContainer[posX * SECTION_SIZE * this.verticalDataCount + posZ * this.verticalDataCount + verticalIndex] = RenderDataPointUtil.EMPTY_DATA;
		}
	}
	
	public boolean setDataPoint(long data, int posX, int posZ, int verticalIndex)
	{
		this.renderDataContainer[posX * SECTION_SIZE * this.verticalDataCount + posZ * this.verticalDataCount + verticalIndex] = data;
		return true;
	}
	
	public boolean copyVerticalData(IColumnDataView newData, int posX, int posZ, boolean overwriteDataWithSameGenerationMode)
	{
		if (DO_SAFETY_CHECKS)
		{
			if (newData.size() != this.verticalDataCount)
				throw new IllegalArgumentException("newData size not the same as this column's vertical size");
			if (posX < 0 || posX >= SECTION_SIZE)
				throw new IllegalArgumentException("X position is out of bounds");
			if (posZ < 0 || posZ >= SECTION_SIZE)
				throw new IllegalArgumentException("Z position is out of bounds");
		}
		
		int dataOffset = posX * SECTION_SIZE * this.verticalDataCount + posZ * this.verticalDataCount;
		int compare = RenderDataPointUtil.compareDatapointPriority(newData.get(0), this.renderDataContainer[dataOffset]);
		if (overwriteDataWithSameGenerationMode)
		{
			if (compare < 0)
			{
				return false;
			}
		}
		else
		{
			if (compare <= 0)
			{
				return false;
			}
		}
		
		// copy the newData into this column's data
		newData.copyTo(this.renderDataContainer, dataOffset, newData.size());
		return true;
	}
	
	
	public long getFirstDataPoint(int posX, int posZ) { return getDataPoint(posX, posZ, 0); }
	public long getDataPoint(int posX, int posZ, int verticalIndex) { return this.renderDataContainer[posX * SECTION_SIZE * this.verticalDataCount + posZ * this.verticalDataCount + verticalIndex]; }
	
	public long[] getVerticalDataPointArray(int posX, int posZ)
	{
		long[] result = new long[this.verticalDataCount];
		int index = posX * SECTION_SIZE * this.verticalDataCount + posZ * this.verticalDataCount;
		System.arraycopy(this.renderDataContainer, index, result, 0, this.verticalDataCount);
		return result;
	}
	
	public ColumnArrayView getVerticalDataPointView(int posX, int posZ)
	{
		return new ColumnArrayView(this.renderDataContainer, this.verticalDataCount,
				posX * SECTION_SIZE * this.verticalDataCount + posZ * this.verticalDataCount,
				this.verticalDataCount);
	}
	
	public ColumnQuadView getFullQuadView() { return this.getQuadViewOverRange(0, 0, SECTION_SIZE, SECTION_SIZE); }
	public ColumnQuadView getQuadViewOverRange(int quadX, int quadZ, int quadXSize, int quadZSize) { return new ColumnQuadView(this.renderDataContainer, SECTION_SIZE, this.verticalDataCount, quadX, quadZ, quadXSize, quadZSize); }
	
	public int getVerticalSize() { return this.verticalDataCount; }
	
	
	
	//========================//
	// data update and output //
	//========================//
	
	public void writeData(DhDataOutputStream outputStream) throws IOException
	{
		outputStream.flush();
		
		outputStream.writeByte(this.getDataDetail());
		outputStream.writeInt(this.verticalDataCount);
		
		if (this.isEmpty)
		{
			// no data is present
			outputStream.writeByte(NO_DATA_FLAG_BYTE);
		}
		else
		{
			// data is present
			outputStream.writeByte(DATA_GUARD_BYTE);
			outputStream.writeInt(this.yOffset);
			
			// write the data for each column
			for (int xz = 0; xz < SECTION_SIZE * SECTION_SIZE; xz++)
			{
				for (int y = 0; y < this.verticalDataCount; y++)
				{
					long currentDatapoint = this.renderDataContainer[xz * this.verticalDataCount + y];
					outputStream.writeLong(Long.reverseBytes(currentDatapoint)); // the reverse bytes is necessary to ensure the data is read in correctly
				}
			}
		}
		
		outputStream.writeByte(DATA_GUARD_BYTE);
		outputStream.writeByte(this.worldGenStep.value);
		
		outputStream.flush();
	}
	
	/** Overrides any data that has not been written directly using write(). Skips empty source dataPoints. */
	public void updateFromRenderSource(ColumnRenderSource renderSource)
	{
		// validate we are writing for the same location
		LodUtil.assertTrue(renderSource.sectionPos.equals(this.sectionPos));
		
		// change the vertical size if necessary (this can happen if the vertical quality was changed in the config) 
		this.clearAndChangeVerticalSize(renderSource.verticalDataCount);
		// validate both objects have the same number of dataPoints
		LodUtil.assertTrue(renderSource.verticalDataCount == this.verticalDataCount);
		
		
		if (renderSource.isEmpty)
		{
			// the source is empty, don't attempt to update anything
			return;
		}
		// the source isn't empty, this object won't be empty after the method finishes
		this.isEmpty = false;
		
		for (int i = 0; i < this.renderDataContainer.length; i += this.verticalDataCount)
		{
			int thisGenMode = RenderDataPointUtil.getGenerationMode(this.renderDataContainer[i]);
			int srcGenMode = RenderDataPointUtil.getGenerationMode(renderSource.renderDataContainer[i]);
			
			if (srcGenMode == 0)
			{
				// the source hasn't been generated, don't write it
				continue;
			}
			
			// this object's column is older than the source's column, update it
			if (thisGenMode <= srcGenMode)
			{
				ColumnArrayView thisColumnArrayView = new ColumnArrayView(this.renderDataContainer, this.verticalDataCount, i, this.verticalDataCount);
				ColumnArrayView srcColumnArrayView = new ColumnArrayView(renderSource.renderDataContainer, renderSource.verticalDataCount, i, renderSource.verticalDataCount);
				thisColumnArrayView.copyFrom(srcColumnArrayView);
				
				this.debugSourceFlags[i / this.verticalDataCount] = renderSource.debugSourceFlags[i / this.verticalDataCount];
			}
		}
		localVersion.incrementAndGet();
	}
	/**
	 * If the newVerticalSize is different than the current verticalSize,
	 * this will delete any data currently in this object and re-size it. <Br>
	 * Otherwise this method will do nothing.
	 */
	private void clearAndChangeVerticalSize(int newVerticalSize)
	{
		if (newVerticalSize != this.verticalDataCount)
		{
			this.verticalDataCount = newVerticalSize;
			this.renderDataContainer = new long[SECTION_SIZE * SECTION_SIZE * this.verticalDataCount];
			localVersion.incrementAndGet();
		}
	}
	
	public boolean fastWrite(ChunkSizedFullDataAccessor chunkData, IDhClientLevel level)
	{
		try
		{
			if (FullDataToRenderDataTransformer.writeFullDataChunkToColumnData(this, level, chunkData))
			{
				localVersion.incrementAndGet();
				return true;
			}
			else
			{
				return false;
			}
		}
		catch (InterruptedException e)
		{
			// expected if the transformer is shut down, the exception can be ignored
			LOGGER.warn(ColumnRenderSource.class.getSimpleName() + " fast write interrupted.");
		}
		catch (Throwable e)
		{
			// shouldn't happen, but just in case
			LOGGER.warn("Unable to complete fastWrite for RenderSource pos: [" + this.sectionPos + "] and chunk pos: [" + chunkData.pos + "].", e);
		}
		return false;
	}
	
	
	
	//=====================//
	// data helper methods //
	//=====================//
	
	public boolean doesDataPointExist(int posX, int posZ) { return RenderDataPointUtil.doesDataPointExist(this.getFirstDataPoint(posX, posZ)); }
	
	public void generateData(ColumnRenderSource lowerDataContainer, int posX, int posZ)
	{
		ColumnArrayView outputView = this.getVerticalDataPointView(posX, posZ);
		ColumnQuadView quadView = lowerDataContainer.getQuadViewOverRange(posX * 2, posZ * 2, 2, 2);
		outputView.mergeMultiDataFrom(quadView);
	}
	
	public int getMaxLodCount() { return SECTION_SIZE * SECTION_SIZE * this.getVerticalSize(); }
	
	public long getRoughRamUsageInBytes() { return (long) this.renderDataContainer.length * Long.BYTES; }
	
	public DhSectionPos getSectionPos() { return this.sectionPos; }
	
	public byte getDataDetail() { return (byte) (this.sectionPos.sectionDetailLevel - SECTION_SIZE_OFFSET); }
	
	/** @return how many data points wide this {@link ColumnRenderSource} is. */
	public int getWidthInDataPoints() { return BitShiftUtil.powerOfTwo(this.getDetailOffset()); }
	public byte getDetailOffset() { return SECTION_SIZE_OFFSET; }
	
	public byte getRenderDataFormatVersion() { return DATA_FORMAT_VERSION; }
	
	/**
	 * Whether this object is still valid. If not, a new one should be created.
	 * TODO this will be necessary for dedicated multiplayer support, if the server has newer data this section should no longer be valid
	 */
	public boolean isValid() { return true; }
	
	public boolean isEmpty() { return this.isEmpty; }
	public void markNotEmpty() { this.isEmpty = false; }
	
	
	
	//=======//
	// debug //
	//=======//
	
	/** Sets the debug flag for the given area */
	public void fillDebugFlag(int xStart, int zStart, int xWidth, int zWidth, DebugSourceFlag flag)
	{
		for (int x = xStart; x < xStart + xWidth; x++)
		{
			for (int z = zStart; z < zStart + zWidth; z++)
			{
				this.debugSourceFlags[x * SECTION_SIZE + z] = flag;
			}
		}
		localVersion.incrementAndGet();
	}
	
	public DebugSourceFlag debugGetFlag(int ox, int oz) { return this.debugSourceFlags[ox * SECTION_SIZE + oz]; }
	
	
	
	//==============//
	// base methods //
	//==============//
	
	@Override
	public String toString()
	{
		String LINE_DELIMITER = "\n";
		String DATA_DELIMITER = " ";
		String SUBDATA_DELIMITER = ",";
		StringBuilder stringBuilder = new StringBuilder();
		
		stringBuilder.append(this.sectionPos);
		stringBuilder.append(LINE_DELIMITER);
		
		int size = this.sectionPos.getWidth().numberOfLodSectionsWide;
		for (int z = 0; z < size; z++)
		{
			for (int x = 0; x < size; x++)
			{
				for (int y = 0; y < this.verticalDataCount; y++)
				{
					//Converting the dataToHex
					stringBuilder.append(Long.toHexString(this.getDataPoint(x, z, y)));
					if (y != this.verticalDataCount - 1)
						stringBuilder.append(SUBDATA_DELIMITER);
				}
				
				if (x != size - 1)
					stringBuilder.append(DATA_DELIMITER);
			}
			
			if (z != size - 1)
				stringBuilder.append(LINE_DELIMITER);
		}
		return stringBuilder.toString();
	}
	
	
	
	//==============//
	// helper enums //
	//==============//
	
	public enum DebugSourceFlag
	{
		FULL(ColorUtil.BLUE),
		DIRECT(ColorUtil.WHITE),
		SPARSE(ColorUtil.YELLOW),
		FILE(ColorUtil.BROWN);
		
		public final int color;
		
		DebugSourceFlag(int color) { this.color = color; }
	}
	
}
