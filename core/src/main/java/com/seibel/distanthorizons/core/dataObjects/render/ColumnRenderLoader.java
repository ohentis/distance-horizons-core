package com.seibel.distanthorizons.core.dataObjects.render;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IIncompleteFullDataSource;
import com.seibel.distanthorizons.core.file.renderfile.RenderMetaDataFile;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.transformers.FullDataToRenderDataTransformer;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Handles loading and parsing {@link RenderMetaDataFile}s to create {@link ColumnRenderSource}s. <br><br>
 *
 * Please see the {@link ColumnRenderLoader#loadRenderSource} method to see what
 * file versions this class can handle.
 */
public class ColumnRenderLoader
{
	public static ColumnRenderLoader INSTANCE = new ColumnRenderLoader();
	
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	
	
	private ColumnRenderLoader() { }
	
	
	
	public ColumnRenderSource loadRenderSource(RenderMetaDataFile dataFile, DhDataInputStream inputStream, IDhLevel level) throws IOException
	{
		int dataFileVersion = dataFile.baseMetaData.binaryDataFormatVersion;
		
		switch (dataFileVersion)
		{
			case 1:
				//LOGGER.info("loading render source "+dataFile.pos);
				
				ParsedColumnData parsedColumnData = readDataV1(inputStream, level.getMinY());
				if (parsedColumnData.isEmpty)
				{
					LOGGER.warn("Empty render file " + dataFile.pos);
				}
				
				return new ColumnRenderSource(dataFile.pos, parsedColumnData, level);
			default:
				throw new IOException("Invalid Data: The data version [" + dataFileVersion + "] is not supported");
		}
	}
	
	/** @throws InterruptedException see {@link FullDataToRenderDataTransformer#transformFullDataToColumnData(IDhClientLevel, CompleteFullDataSource) FullDataToRenderDataTransformer#transformFullDataToColumnData} for documentation */
	public ColumnRenderSource createRenderSource(IFullDataSource fullDataSource, IDhClientLevel level) throws InterruptedException
	{
		if (fullDataSource instanceof CompleteFullDataSource)
		{
			return FullDataToRenderDataTransformer.transformFullDataToColumnData(level, (CompleteFullDataSource) fullDataSource);
		}
		else if (fullDataSource instanceof IIncompleteFullDataSource)
		{
			return FullDataToRenderDataTransformer.transformIncompleteDataToColumnData(level, (IIncompleteFullDataSource) fullDataSource);
		}
		
		LodUtil.assertNotReach();
		return null;
	}
	
	
	
	//========================//
	// versioned file parsing //
	//========================//
	
	/**
	 * @param inputStream Expected format: 1st byte: detail level, 2nd byte: vertical size, 3rd byte on: column data
	 * @throws IOException if there was an issue reading the stream
	 */
	private static ParsedColumnData readDataV1(DhDataInputStream inputStream, int expectedYOffset) throws IOException
	{
		// TODO move into ColumnRenderSource
		
		byte detailLevel = inputStream.readByte();
		
		int verticalDataCount = inputStream.readInt();
		if (verticalDataCount <= 0)
		{
			throw new IOException("Invalid data: vertical size must be 0 or greater");
		}
		
		int maxNumberOfDataPoints = ColumnRenderSource.SECTION_SIZE * ColumnRenderSource.SECTION_SIZE * verticalDataCount;
		
		
		byte dataPresentFlag = inputStream.readByte();
		if (dataPresentFlag != ColumnRenderSource.NO_DATA_FLAG_BYTE && dataPresentFlag != ColumnRenderSource.DATA_GUARD_BYTE)
		{
			throw new IOException("Incorrect render file format. Expected either: NO_DATA_FLAG_BYTE [" + ColumnRenderSource.NO_DATA_FLAG_BYTE + "] or DATA_GUARD_BYTE [" + ColumnRenderSource.DATA_GUARD_BYTE + "], Found: [" + dataPresentFlag + "]");
		}
		else if (dataPresentFlag == ColumnRenderSource.NO_DATA_FLAG_BYTE)
		{
			// no data is present
			return new ParsedColumnData(detailLevel, verticalDataCount, EDhApiWorldGenerationStep.EMPTY, new long[maxNumberOfDataPoints], true);
		}
		else
		{
			// data is present
			
			int fileYOffset = inputStream.readInt();
			if (fileYOffset != expectedYOffset)
			{
				throw new IOException("Invalid data: yOffset is incorrect. Expected: [" + expectedYOffset + "], found: [" + fileYOffset + "].");
			}
			
			
			// read the column data
			byte[] rawByteData = new byte[maxNumberOfDataPoints * Long.BYTES];
			ByteBuffer columnDataByteBuffer = ByteBuffer.wrap(rawByteData).order(ByteOrder.LITTLE_ENDIAN);
			inputStream.readFully(rawByteData);
			
			
			// parse the column data
			long[] dataPoints = new long[maxNumberOfDataPoints];
			columnDataByteBuffer.asLongBuffer().get(dataPoints);
			
			boolean isEmpty = true;
			for (long dataPoint : dataPoints)
			{
				if (dataPoint != 0)
				{
					isEmpty = false;
					break;
				}
			}
			
			
			
			byte guardByteFlag = inputStream.readByte();
			if (guardByteFlag != ColumnRenderSource.DATA_GUARD_BYTE)
			{
				throw new IOException("invalid world gen step end guard");
			}
			EDhApiWorldGenerationStep worldGenStep = EDhApiWorldGenerationStep.fromValue(inputStream.readByte());
			if (worldGenStep == null)
			{
				LOGGER.warn("Missing WorldGenStep, defaulting to: " + EDhApiWorldGenerationStep.SURFACE.name());
				worldGenStep = EDhApiWorldGenerationStep.SURFACE;
			}
			
			
			
			return new ParsedColumnData(detailLevel, verticalDataCount, worldGenStep, dataPoints, isEmpty);
		}
	}
	
	public static class ParsedColumnData
	{
		public final byte detailLevel;
		public final int verticalSize;
		public final EDhApiWorldGenerationStep worldGenStep;
		public final long[] dataContainer;
		public final boolean isEmpty;
		
		public ParsedColumnData(byte detailLevel, int verticalSize, EDhApiWorldGenerationStep worldGenStep, long[] dataContainer, boolean isEmpty)
		{
			this.detailLevel = detailLevel;
			this.verticalSize = verticalSize;
			this.worldGenStep = worldGenStep;
			this.dataContainer = dataContainer;
			this.isEmpty = isEmpty;
		}
		
	}
	
	
}
