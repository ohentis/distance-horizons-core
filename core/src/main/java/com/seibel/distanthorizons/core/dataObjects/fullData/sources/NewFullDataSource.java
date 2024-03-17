/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2023 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.dataObjects.fullData.sources;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder;
import com.seibel.distanthorizons.core.file.IDataSource;
import com.seibel.distanthorizons.core.file.fullDatafile.NewFullDataFileHandler;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.FullDataPointUtilV1;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This data source contains every datapoint over its given {@link DhSectionPos}. <br><br>
 * 
 * TODO create a child object that extends AutoClosable
 *       that can be pooled to reduce GC overhead 
 * 
 * @see FullDataPointUtil
 * @see CompleteFullDataSource
 */
public class NewFullDataSource implements IDataSource<IDhLevel>
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	/** useful for debugging, but can slow down update operations quite a bit due to being called so often. */
	private static final boolean RUN_UPDATE_DEV_VALIDATION = false; //ModInfo.IS_DEV_BUILD;
	private static final boolean RUN_V1_MIGRATION_VALIDATION = false;
	
	/** measured in data columns */
	public static final int WIDTH = 64;
	
	public static final byte DATA_FORMAT_VERSION = 1;
		
	
	
	// TODO make these fields private
	private DhSectionPos pos;
	@Override
	public DhSectionPos getKey() { return this.pos; }
	
	public long lastModifiedUnixDateTime;
	public long createdUnixDateTime;
	
	public int levelMinY;
	
	private int cachedHashCode = 0;
	
	/** 
	 * stores how far each column has been generated should start with {@link EDhApiWorldGenerationStep#EMPTY}
	 * @see EDhApiWorldGenerationStep 
	 */
	public byte[] columnGenerationSteps;
	
	/** 
	 * stored x/z, y 
	 * The y data should be sorted from bottom to top
	 */
	public long[][] dataPoints;
	private boolean isEmpty;
	
	private FullDataPointIdMap mapping;
	public FullDataPointIdMap getMapping() { return this.mapping; }
	
	public boolean applyToParent = false;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public static NewFullDataSource createEmpty(DhSectionPos pos) { return new NewFullDataSource(pos); }
	private NewFullDataSource(DhSectionPos pos) 
	{
		this.pos = pos;
		this.dataPoints = new long[WIDTH * WIDTH][];
		this.mapping = new FullDataPointIdMap(pos);
		this.isEmpty = true;
		
		// doesn't need to be populated since nothing has been generated yet
		// the default value of all 0's is adequate
		this.columnGenerationSteps = new byte[WIDTH * WIDTH];
	}
	
	public static NewFullDataSource createWithData(DhSectionPos pos, FullDataPointIdMap mapping, long[][] data, byte[] columnGenerationStep) { return new NewFullDataSource(pos, mapping, data, columnGenerationStep); }
	private NewFullDataSource(DhSectionPos pos, FullDataPointIdMap mapping, long[][] data, byte[] columnGenerationSteps)
	{
		LodUtil.assertTrue(data.length == WIDTH * WIDTH);
		
		this.pos = pos;
		this.dataPoints = data;
		this.mapping = mapping;
		this.isEmpty = false;
		
		this.columnGenerationSteps = columnGenerationSteps;
	}
	
	public static NewFullDataSource createFromChunk(IChunkWrapper chunkWrapper) { return LodDataBuilder.createGeneratedDataSource(chunkWrapper); }
	
	public static NewFullDataSource createFromCompleteDataSource(CompleteFullDataSource legacyData)
	{
		if (CompleteFullDataSource.WIDTH != WIDTH)
		{
			throw new UnsupportedOperationException(
					"Unable to convert CompleteFullDataSource into NewFullDataSource. " +
					"Data sources have different data point widths and no converter is present. " +
					"CompleteFullDataSource width ["+CompleteFullDataSource.WIDTH+"], NewFullDataSource width ["+WIDTH+"].");
		}
		
		
		// Note: this logic only works if the data point data is the same between both versions
		byte[] columnGenerationSteps = new byte[WIDTH * WIDTH];
		long[][] dataPoints = new long[WIDTH * WIDTH][];
		for (int x = 0; x < WIDTH; x++)
		{
			for (int z = 0; z < WIDTH; z++)
			{
				long[] dataColumn = legacyData.get(x, z);
				if (dataColumn != null && dataColumn.length != 0)
				{
					// reverse the array so index 0 is the lowest,
					// this is necessary for later logic
					// source: https://stackoverflow.com/questions/2137755/how-do-i-reverse-an-int-array-in-java
					for(int i = 0; i < dataColumn.length / 2; i++)
					{
						long temp = dataColumn[i];
						dataColumn[i] = dataColumn[dataColumn.length - i - 1];
						dataColumn[dataColumn.length - i - 1] = temp;
					}
					
					int index = relativePosToIndex(x, z);
					dataPoints[index] = dataColumn;
					
					
					// convert the data point format
					boolean columnHasNonAirBlock = false;
					for (int i = 0; i < dataColumn.length; i++)
					{
						long dataPoint = dataColumn[i];
						
						int id = FullDataPointUtilV1.getId(dataPoint);
						int height = FullDataPointUtilV1.getHeight(dataPoint);
						int bottomY = FullDataPointUtilV1.getBottomY(dataPoint);
						byte blockLight = (byte) FullDataPointUtilV1.getBlockLight(dataPoint);
						byte skyLight = (byte) FullDataPointUtilV1.getSkyLight(dataPoint);
						
						IBlockStateWrapper blockState = legacyData.mapping.getBlockStateWrapper(id);
						if (blockState.isAir())
						{
							// air shouldn't have any light, otherwise down sampling will look weird
							blockLight = 0;
						}
						
						long newDataPoint = FullDataPointUtil.encode(id, height, bottomY, blockLight, skyLight);
						dataColumn[i] = newDataPoint;
						
						
						// check if this datapoint is air
						if (!columnHasNonAirBlock && !blockState.isAir())
						{
							columnHasNonAirBlock = true;
						}
					}
					
					// the old data sources didn't have a generation step written down
					// if the column has any data points, assume it's fully generated, otherwise assume it's empty
					columnGenerationSteps[index] = (columnHasNonAirBlock ? EDhApiWorldGenerationStep.LIGHT.value : EDhApiWorldGenerationStep.EMPTY.value);
				}
			}
		}
		
		NewFullDataSource newFullDataSource = NewFullDataSource.createWithData(legacyData.getSectionPos(), legacyData.mapping, dataPoints, columnGenerationSteps);
		
		
		// should only be used if debugging, this is a very expensive operation
		if (RUN_V1_MIGRATION_VALIDATION)
		{
			for (int x = 0; x < WIDTH; x++)
			{
				for (int z = 0; z < WIDTH; z++)
				{
					long[] legacyDataColumn = legacyData.get(x, z);
					if (legacyDataColumn != null && legacyDataColumn.length != 0)
					{
						long[] newDataColumn = newFullDataSource.get(x, z);
						
						if (newDataColumn == null)
						{
							LodUtil.assertNotReach("Accessor column mismatch");
						}
						else if (legacyDataColumn.length != newDataColumn.length)
						{
							LodUtil.assertNotReach("Accessor column length mismatch");
						}
						else
						{
							for (int i = 0; i < legacyDataColumn.length; i++)
							{
								if (legacyDataColumn[i] != newDataColumn[i])
								{
									LodUtil.assertNotReach("Data mismatch");
								}
							}
						}
						
					}
				}
			}
		}
		
		return newFullDataSource;
	}
	
	
	
	//======//
	// data //
	//======//
	
	public long[] get(int relX, int relZ) throws IndexOutOfBoundsException { return this.dataPoints[relativePosToIndex(relX, relZ)]; }
	
	@Override
	public boolean update(NewFullDataSource inputDataSource, @Nullable IDhLevel level) { return this.update(inputDataSource); }
	public boolean update(NewFullDataSource inputDataSource)
	{
		// shouldn't happen, but James saw it happen once
		if (inputDataSource.mapping.getMaxValidId() == 0)
		{
			LOGGER.warn("Invalid mapping given from input update data source at pos: ["+inputDataSource.pos+"], skipping update.");
			return false;
		}
		
		
		
		byte thisDetailLevel = this.pos.getDetailLevel();
		byte inputDetailLevel = inputDataSource.pos.getDetailLevel();
		
		
		// determine the mapping changes necessary for the input to map onto this datasource
		int[] remappedIds = this.mapping.mergeAndReturnRemappedEntityIds(inputDataSource.mapping);
		
		boolean dataChanged;
		if (inputDetailLevel == thisDetailLevel)
		{
			dataChanged = this.updateFromSameDetailLevel(inputDataSource, remappedIds);
		}
		else if (inputDetailLevel + 1 == thisDetailLevel)
		{
			dataChanged = this.updateFromOneBelowDetailLevel(inputDataSource, remappedIds);
		}
		else
		{
			// other detail levels aren't supported since it would be more difficult to maintain
			// and would lead to edge cases that don't necessarily need to be supported 
			// (IE what do you do when the input is smaller than a single datapoint in the receiving data source?)
			// instead it's better to just percolate the updates up
			throw new UnsupportedOperationException("Unsupported data source update. Expected input detail level of ["+thisDetailLevel+"] or ["+(thisDetailLevel+1)+"], received detail level ["+inputDetailLevel+"].");
		}
		
		if (dataChanged && this.pos.getDetailLevel() < NewFullDataFileHandler.TOP_SECTION_DETAIL_LEVEL)
		{
			// mark that this data source should be applied to its parent
			this.applyToParent = true;
		}
		
		if (dataChanged)
		{
			// update the hash code
			this.generateHashCode();
		}
		
		return dataChanged;
	}
	public boolean updateFromSameDetailLevel(NewFullDataSource inputDataSource, int[] remappedIds)
	{
		// both data sources should have the same detail level
		if (inputDataSource.pos.getDetailLevel() != this.pos.getDetailLevel())
		{
			throw new IllegalArgumentException("Both data sources must have the same detail level. Expected ["+this.pos.getDetailLevel()+"], received ["+inputDataSource.pos.getDetailLevel()+"].");
		}
		
		// copy over everything from the input data source into this one
		// provided there is data to copy and the world generation step is the same or more complete
		boolean dataChanged = false;
		for (int x = 0; x < WIDTH; x++)
		{
			for (int z = 0; z < WIDTH; z++)
			{
				int index = relativePosToIndex(x, z);
				
				long[] newDataArray = inputDataSource.dataPoints[index];
				if (newDataArray != null)
				{
					byte thisGenState = this.columnGenerationSteps[index];
					byte inputGenState = inputDataSource.columnGenerationSteps[index];
					
					if (inputGenState != EDhApiWorldGenerationStep.EMPTY.value
						&& thisGenState <= inputGenState)
					{
						long[] oldDataArray = this.dataPoints[index];
						
						// copy over the new data
						this.dataPoints[index] = new long[newDataArray.length];
						System.arraycopy(newDataArray, 0, this.dataPoints[index], 0, newDataArray.length);
						this.remapDataColumn(index, remappedIds);
						
						// we only need to see if the data was changed in one column
						if (!dataChanged)
						{
							// needs to be done after the ID's have been remapped otherwise the ID's won't match even if the data is the same
							dataChanged = areDataColumnsDifferent(oldDataArray, this.dataPoints[index]);
						}
						
						this.columnGenerationSteps[index] = inputGenState;
						this.isEmpty = false;
					}
				}
			}
		}
		
		return dataChanged;
	}
	public boolean updateFromOneBelowDetailLevel(NewFullDataSource inputDataSource, int[] remappedIds)
	{
		if (inputDataSource.pos.getDetailLevel() + 1 != this.pos.getDetailLevel())
		{
			throw new IllegalArgumentException("Input data source must be exactly 1 detail level below this data source. Expected ["+(this.pos.getDetailLevel() - 1)+"], received ["+inputDataSource.pos.getDetailLevel()+"].");
		}
		
		// input is one detail level lower (higher detail)
		// so 2x2 input data points will be converted into 1 recipient data point
		
		
		// determine where in the input data source should be written to
		// since the input is one detail level below it will be one of this position's 4 children
		int minChildXPos = this.pos.getChildByIndex(0).getX();
		int recipientOffsetX = (inputDataSource.pos.getX() == minChildXPos) ? 0 : (WIDTH / 2);
		int minChildZPos = this.pos.getChildByIndex(0).getZ();
		int recipientOffsetZ = (inputDataSource.pos.getZ() == minChildZPos) ? 0 : (WIDTH / 2);
		
		
		
		// merge the input's data points
		// into this data source's
		boolean dataChanged = false;
		for (int x = 0; x < WIDTH; x += 2)
		{
			for (int z = 0; z < WIDTH; z += 2)
			{
				long[] mergedInputDataArray = mergeInputTwoByTwoDataColumn(inputDataSource, x, z);
				// TODO
				byte inputGenStep = inputDataSource.columnGenerationSteps[0];


				int recipientX = (x / 2) + recipientOffsetX;
				int recipientZ = (z / 2) + recipientOffsetZ;
				int recipientIndex = relativePosToIndex(recipientX, recipientZ);

				long[] oldDataArray = this.dataPoints[recipientIndex];

				this.columnGenerationSteps[recipientIndex] = inputGenStep;
				this.dataPoints[recipientIndex] = mergedInputDataArray;
				this.remapDataColumn(recipientIndex, remappedIds);

				// we only need to see if the data was changed in one column
				if (!dataChanged)
				{
					// needs to be done after the ID's have been remapped otherwise the ID's won't match even if the data is the same
					dataChanged = areDataColumnsDifferent(oldDataArray, this.dataPoints[recipientIndex]);
				}

				this.isEmpty = false;
			}
		}
		
		return dataChanged;
	}
	private static long[] mergeInputTwoByTwoDataColumn(NewFullDataSource inputDataSource, int x, int z)
	{
		ArrayList<Long> newColumnList = new ArrayList<>();
		
		int[] currentDatapointIndex = new int[] { 0, 0, 0, 0 };
		
		int lastId = 0;
		byte lastBlockLight = 0;
		byte lastSkyLight = 0;
		int height = 0;
		int minY = 0;
		
		
		for (int blockY = 0; blockY < RenderDataPointUtil.MAX_WORLD_Y_SIZE; blockY++, height++)
		{
			// if each column has reached the end of their data, nothing more needs to be done
			if (currentDatapointIndex[0] == -1
				&& currentDatapointIndex[1] == -1
				&& currentDatapointIndex[2] == -1
				&& currentDatapointIndex[3] == -1
				)
			{
				break;
			}
			
			
			long[] datapointsForYSlice = new long[4]; 
			
			
			// scary double loop but, 
			// this will only ever loop 4 times, 
			// once for each of the 4 input columns
			int colIndex = 0;
			for (int inputX = x; inputX < x +2; inputX++)
			{
				for (int inputZ = z; inputZ < z + 2; inputZ++, colIndex++)
				{
					// TODO throw an assertion if the column isn't in order or just fix it...
					long[] inputDataArray = inputDataSource.dataPoints[relativePosToIndex(inputX, inputZ)];
					if (inputDataArray == null || inputDataArray.length == 0)
					{
						currentDatapointIndex[colIndex] = -1;
						continue;
					}
					
					
					int dataPointIndex = currentDatapointIndex[colIndex];
					if (dataPointIndex == -1)
					{
						// went over the end 
						continue;
					}
					long datapoint = inputDataArray[dataPointIndex];
					
					int datapointMinY = FullDataPointUtil.getBottomY(datapoint);
					int numbOfBlocksTall = FullDataPointUtil.getHeight(datapoint);
					int datapointMaxY = (datapointMinY + numbOfBlocksTall);
					
					
					// check if y position is inside this datapoint
					if (blockY < datapointMinY)
					{
						// this y-slice is below this datapoint, nothing can be added
						continue;
					}
					else if (blockY >= datapointMaxY)
					{
						// this y-slice is above this datapoint,
						// try the next data point
						
						int newDatapointIndex = currentDatapointIndex[colIndex] + 1;
						if (newDatapointIndex >= inputDataArray.length)
						{
							// went to far, no additional data present
							newDatapointIndex = -1;
						}
						currentDatapointIndex[colIndex] = newDatapointIndex;
						
						
						// try again with the next data point
						inputZ--;
						colIndex--;
						continue;
					}
					
					
					
					datapointsForYSlice[colIndex] = datapoint;
				}
			}
			
			
			
			int[] mergeIds = new int[4];
			int[] mergeBlockLights = new int[4];
			int[] mergeSkyLights = new int[4];
			for (int i = 0; i < 4; i++)
			{
				mergeIds[i] = FullDataPointUtil.getId(datapointsForYSlice[i]);
				mergeBlockLights[i] = FullDataPointUtil.getBlockLight(datapointsForYSlice[i]);
				mergeSkyLights[i] = FullDataPointUtil.getSkyLight(datapointsForYSlice[i]);
			}
			
			
			// determine the most common values for this slice
			int id = determineMostValueInColumnSlice(mergeIds, inputDataSource.mapping);
			byte blockLight = (byte) determineAverageValueInColumnSlice(mergeBlockLights);
			byte skyLight = (byte) determineAverageValueInColumnSlice(mergeSkyLights);

			// if this slice is different then the last one, create a new one
			if (id != lastId
				// block and sky light might not be necessary
				|| blockLight != lastBlockLight
				|| skyLight != lastSkyLight)
			{
				if (height != 0)
				{
					newColumnList.add(FullDataPointUtil.encode(lastId, height, minY, lastBlockLight, lastSkyLight));
				}
				
				lastId = id;
				lastBlockLight = blockLight;
				lastSkyLight = skyLight;
				height = 0;
				minY = blockY;
			}
		}
		
		// add the last slice if present
		if (height != 0)
		{
			newColumnList.add(FullDataPointUtil.encode(lastId, height, minY, lastBlockLight, lastSkyLight));
		}
		
		
		
		
		long[]mergedInputDataArray = new long[newColumnList.size()];
		for (int i = 0; i < mergedInputDataArray.length; i++)
		{
			mergedInputDataArray[i] = newColumnList.get(i);
		}
		return mergedInputDataArray;
	}
	/**
	 * Only update the ID once it's been added to this data source.
	 * Updating the incoming data source will cause issues if it is applied 
	 * to anything else due to multiple remapping.
	 */
	private void remapDataColumn(int dataPointIndex, int[] remappedIds)
	{
		long[] dataColumn = this.dataPoints[dataPointIndex];
		for (int i = 0; i < dataColumn.length; i++)
		{
			dataColumn[i] = FullDataPointUtil.remap(remappedIds, dataColumn[i]);
		}
	}
	private static boolean areDataColumnsDifferent(long[] oldDataArray, long[] newDataArray)
	{
		if (oldDataArray == null || oldDataArray.length != newDataArray.length)
		{
			// new data was added/removed
			return true;
		}
		else
		{
			// check if the new column data is different
			int oldArrayHash = Arrays.hashCode(oldDataArray);
			int newArrayHash = Arrays.hashCode(newDataArray);
			return (newArrayHash != oldArrayHash);
		}
	}
	/** @param mapping can be included to ignore air ID's, otherwise all 4 values are treated equally */
	private static int determineMostValueInColumnSlice(int[] sliceArray, @Nullable FullDataPointIdMap mapping)
	{
		if (RUN_UPDATE_DEV_VALIDATION)
		{
			LodUtil.assertTrue(sliceArray.length == 4, "Column Slice should only contain 4 values.");
		}
			
		int value0 = sliceArray[0];
		int count0 = 0;
		int value1 = sliceArray[1];
		int count1 = 0;
		int value2 = sliceArray[2];
		int count2 = 0;
		int value3 = sliceArray[3];
		int count3 = 0; 
		
		// count the occurrences of each value
		for (int i = 0; i < 4; i++)
		{
			int value = sliceArray[i];
			if (mapping != null && mapping.getBlockStateWrapper(value).isAir())
			{
				// always overwrite air to prevent holes in hollow structures
				continue;
			}
			
			if (value == value0)
				count0++;
			else if (value == value1)
				count1++;
			else if (value == value2)
				count2++;
			else
				count3++;
		}
		
		// return the most common occurance
		int maxCount = Math.max(count0, Math.max(count1, Math.max(count2, count3)));
		if (maxCount == count0)
			// if the max count is 1 then we'll just go with the first column
			return value0;
		else if (maxCount == count1)
			return value1;
		else if (maxCount == count2)
			return value2;
		else 
			return value3;
	}
	private static int determineAverageValueInColumnSlice(int[] sliceArray)
	{
		if (RUN_UPDATE_DEV_VALIDATION)
		{
			LodUtil.assertTrue(sliceArray.length == 4, "Column Slice should only contain 4 values.");
		}
		
		
		int value = 0;
		for (int i = 0; i < 4; i++)
		{
			value += sliceArray[i];
		}
		
		value /= 4;
		return value;
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	// TODO  make private, any external logic should go through a method, not interact with the arrays directly
	public static int relativePosToIndex(int relX, int relZ) throws IndexOutOfBoundsException
	{ 
		if (relX < 0 || relZ < 0 ||
			relX > WIDTH || relZ > WIDTH)
		{
			throw new IndexOutOfBoundsException("Relative data source positions must be between [0] and ["+WIDTH+"] (inclusive) the relative pos: ["+relX+","+relZ+"] is outside of those boundaries.");
		}
		
		return (relX * WIDTH) + relZ; 
	}
	
	
	
	//=====================//
	// setters and getters //
	//=====================//
	
	@Override
	public DhSectionPos getSectionPos() { return this.pos; }
	
	@Override
	public byte getDataDetailLevel() { return (byte) (this.pos.getDetailLevel() - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL); }
	
	@Override
	public byte getDataFormatVersion() { return DATA_FORMAT_VERSION; }
	
	@Deprecated
	@Override
	public EDhApiWorldGenerationStep getWorldGenStep() { return this.getWorldGenStepAtRelativePos(0, 0); }
	@Override
	public EDhApiWorldGenerationStep getWorldGenStepAtRelativePos(int relX, int relZ) 
	{
		int index = relativePosToIndex(relX, relZ);
		return EDhApiWorldGenerationStep.fromValue(this.columnGenerationSteps[index]); 
	}
	
	public boolean isEmpty() { return this.isEmpty; }
	public void markNotEmpty() { this.isEmpty = false; }
	
	public void setSingleColumn(long[] longArray, int relX, int relZ, EDhApiWorldGenerationStep worldGenStep)
	{
		int index = relativePosToIndex(relX, relZ);
		this.dataPoints[index] = longArray;
		this.columnGenerationSteps[index] =  worldGenStep.value;
		
		
		if (RUN_UPDATE_DEV_VALIDATION)
		{
			// validate the incoming ID's
			int maxValidId = this.mapping.getMaxValidId();
			for (int i = 0; i < longArray.length; i++)
			{
				long dataPoint = longArray[i];
				int id = FullDataPointUtil.getId(dataPoint);
				if (id > maxValidId)
				{
					LodUtil.assertNotReach("Column set with higher than possible ID. ID [" + id + "], max valid ID [" + maxValidId + "].");
				}
			}
		}
	}
	
	@Override
	public String toString() { return this.pos.toString(); }
	
	@Override 
	public int hashCode()
	{
		if (this.cachedHashCode == 0)
		{
			this.generateHashCode();
		}
		return this.cachedHashCode;
	}
	private void generateHashCode()
	{
		int result = this.pos.hashCode();
		result = 31 * result + Arrays.deepHashCode(this.dataPoints);
		result = 17 * result + Arrays.hashCode(this.columnGenerationSteps);
		
		this.cachedHashCode = result;
	}
	
	@Override 
	public boolean equals(Object obj)
	{
		if (!(obj instanceof NewFullDataSource))
		{
			return false;
		}
		NewFullDataSource other = (NewFullDataSource) obj;
		
		if (!other.pos.equals(this.pos))
		{
			return false;
		}
		else
		{
			// the positions are the same, use the hash as a quick method
			// to determine if the data inside is the same.
			// Note: this isn't perfect, but should work well enough for our use case.
			return other.hashCode() == this.hashCode();	
		}
	}
	
	
	
	//============//
	// deprecated //
	//============//
	
	@Deprecated
	@Override
	public void writeToStream(DhDataOutputStream outputStream, IDhLevel level)
	{
		throw new UnsupportedOperationException("deprecated");
	}
	
	
	
	//=========//
	// pooling //
	//=========//
	
	// TODO add pooled data sources
	private static class Pooling
	{
		/** used when pooling data sources */
		private final ArrayList<CompleteFullDataSource> cachedSources = new ArrayList<>();
		private final ReentrantLock cacheLock = new ReentrantLock();
		
		
		/** @return null if no pooled source exists */
		public CompleteFullDataSource tryGetPooledSource()
		{
			try
			{
				this.cacheLock.lock();
				
				int index = this.cachedSources.size() - 1;
				if (index == -1)
				{
					return null;
				}
				else
				{
					return this.cachedSources.remove(index);
				}
			}
			finally
			{
				this.cacheLock.unlock();
			}
		}
		
		/**
		 * Doesn't have to be called, if a data source isn't returned, nothing will be leaked. 
		 * It just means a new source must be constructed next time {@link Pooling#tryGetPooledSource} is called.
		 */
		public void returnPooledDataSource(CompleteFullDataSource dataSource)
		{
			if (dataSource == null)
			{
				return;
			}
			else if (this.cachedSources.size() > 25)
			{
				return;
			}
			
			try
			{
				this.cacheLock.lock();
				this.cachedSources.add(dataSource);
			}
			finally
			{
				this.cacheLock.unlock();
			}
		}
		
	}
	
	
}
