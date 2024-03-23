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
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtilV2;
import com.seibel.distanthorizons.core.util.FullDataPointUtilV1;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.coreapi.ModInfo;
import it.unimi.dsi.fastutil.longs.LongArrayList;
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
 * @see FullDataPointUtilV2
 * @see FullDataSourceV1
 */
public class FullDataSourceV2 implements IDataSource<IDhLevel>
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	/** useful for debugging, but can slow down update operations quite a bit due to being called so often. */
	private static final boolean RUN_UPDATE_DEV_VALIDATION = false;
	private static final boolean RUN_V1_MIGRATION_CONSTRUCTOR_VALIDATION = ModInfo.IS_DEV_BUILD;
	/** 
	 * If the data column order isn't correct
	 * block lighting may appear broken 
	 * and/or certain detail level LODs may not appear at all. 
	 */
	private static final boolean RUN_DATA_ORDER_VALIDATION = ModInfo.IS_DEV_BUILD;
	
	/** measured in data columns */
	public static final int WIDTH = 64;
	
	public static final byte DATA_FORMAT_VERSION = 1;
		
	
	
	private int cachedHashCode = 0;
	
	private DhSectionPos pos;
	@Override
	public DhSectionPos getKey() { return this.pos; }
	
	
	public final FullDataPointIdMap mapping;
	
	
	public long lastModifiedUnixDateTime;
	public long createdUnixDateTime;
	
	public int levelMinY;
	
	/** 
	 * stores how far each column has been generated should start with {@link EDhApiWorldGenerationStep#EMPTY}
	 * @see EDhApiWorldGenerationStep 
	 */
	public byte[] columnGenerationSteps;
	
	/** 
	 * stored x/z, y <br>
	 * The y data should be sorted from top to bottom <br>
	 * TODO that ordering feels weird, it'd be nice to reverse that order, unfortunately
	 *      there's something in the render data logic that expects this order so we can't change it right now
	 */
	public LongArrayList[] dataPoints;
	
	public boolean isEmpty;
	public boolean applyToParent = false;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public static FullDataSourceV2 createEmpty(DhSectionPos pos) { return new FullDataSourceV2(pos); }
	private FullDataSourceV2(DhSectionPos pos) 
	{
		this.pos = pos;
		this.dataPoints = new LongArrayList[WIDTH * WIDTH];
		this.mapping = new FullDataPointIdMap(pos);
		this.isEmpty = true;
		
		// doesn't need to be populated since nothing has been generated yet
		// the default value of all 0's is adequate
		this.columnGenerationSteps = new byte[WIDTH * WIDTH];
	}
	
	public static FullDataSourceV2 createWithData(DhSectionPos pos, FullDataPointIdMap mapping, LongArrayList[] data, byte[] columnGenerationStep) { return new FullDataSourceV2(pos, mapping, data, columnGenerationStep); }
	private FullDataSourceV2(DhSectionPos pos, FullDataPointIdMap mapping, LongArrayList[] data, byte[] columnGenerationSteps)
	{
		LodUtil.assertTrue(data.length == WIDTH * WIDTH);
		
		this.pos = pos;
		this.dataPoints = data;
		this.mapping = mapping;
		this.isEmpty = false;
		
		this.columnGenerationSteps = columnGenerationSteps;
	}
	
	public static FullDataSourceV2 createFromChunk(IChunkWrapper chunkWrapper) { return LodDataBuilder.createGeneratedDataSource(chunkWrapper); }
	
	public static FullDataSourceV2 createFromLegacyDataSourceV1(FullDataSourceV1 legacyData)
	{
		if (FullDataSourceV1.WIDTH != WIDTH)
		{
			throw new UnsupportedOperationException(
					"Unable to convert ["+FullDataSourceV1.class.getSimpleName()+"] into ["+FullDataSourceV2.class.getSimpleName()+"]. " +
					"Data sources have different data point widths and no converter is present. " +
					"input width ["+ FullDataSourceV1.WIDTH+"], recipient width ["+WIDTH+"].");
		}
		
		
		// Note: this logic only works if the data point data is the same between both versions
		byte[] columnGenerationSteps = new byte[WIDTH * WIDTH];
		LongArrayList[] dataPoints = new LongArrayList[WIDTH * WIDTH];
		for (int x = 0; x < WIDTH; x++)
		{
			for (int z = 0; z < WIDTH; z++)
			{
				long[] dataColumn = legacyData.get(x, z);
				if (dataColumn != null && dataColumn.length != 0)
				{
					int index = relativePosToIndex(x, z);
					dataPoints[index] = new LongArrayList(dataColumn);
					
					
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
						
						long newDataPoint = FullDataPointUtilV2.encode(id, height, bottomY, blockLight, skyLight);
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
		
		FullDataSourceV2 fullDataSource = FullDataSourceV2.createWithData(legacyData.getSectionPos(), legacyData.mapping, dataPoints, columnGenerationSteps);
		
		
		// should only be used if debugging, this is a very expensive operation
		if (RUN_V1_MIGRATION_CONSTRUCTOR_VALIDATION)
		{
			for (int x = 0; x < WIDTH; x++)
			{
				for (int z = 0; z < WIDTH; z++)
				{
					long[] legacyDataColumn = legacyData.get(x, z);
					if (legacyDataColumn != null && legacyDataColumn.length != 0)
					{
						LongArrayList newDataColumn = fullDataSource.get(x, z);
						if (newDataColumn == null)
						{
							LodUtil.assertNotReach("Accessor column mismatch");
						}
						else if (legacyDataColumn.length != newDataColumn.size())
						{
							LodUtil.assertNotReach("Accessor column length mismatch");
						}
						else
						{
							for (int i = 0; i < legacyDataColumn.length; i++)
							{
								if (legacyDataColumn[i] != newDataColumn.getLong(i))
								{
									LodUtil.assertNotReach("Data mismatch");
								}
							}
						}
						
					}
				}
			}
		}
		
		return fullDataSource;
	}
	
	
	
	//======//
	// data //
	//======//
	
	public LongArrayList get(int relX, int relZ) throws IndexOutOfBoundsException { return this.dataPoints[relativePosToIndex(relX, relZ)]; }
	
	@Override
	public boolean update(FullDataSourceV2 inputDataSource, @Nullable IDhLevel level) { return this.update(inputDataSource); }
	public boolean update(FullDataSourceV2 inputDataSource)
	{
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
		
		if (dataChanged && this.pos.getDetailLevel() < FullDataSourceProviderV2.TOP_SECTION_DETAIL_LEVEL)
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
	public boolean updateFromSameDetailLevel(FullDataSourceV2 inputDataSource, int[] remappedIds)
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
				
				LongArrayList inputDataArray = inputDataSource.dataPoints[index];
				if (inputDataArray != null)
				{
					byte thisGenState = this.columnGenerationSteps[index];
					byte inputGenState = inputDataSource.columnGenerationSteps[index];
					
					if (inputGenState != EDhApiWorldGenerationStep.EMPTY.value
						&& thisGenState <= inputGenState)
					{
						// check if the data changed
						if (this.dataPoints[index] == null)
						{
							// no data was present previously
							this.dataPoints[index] = new LongArrayList(inputDataArray);
							dataChanged = true;
						}
						else if (this.dataPoints[index].size() != inputDataArray.size())
						{
							// data is present, but the size is different
							dataChanged = true;
						}
						
						int oldDataHash = 0;
						if (!dataChanged)
						{
							// some old data existed with the same length,
							// we'll have to compare the caches
							oldDataHash = this.dataPoints[index].hashCode();
						}
						
						
						// copy over the new data
						this.dataPoints[index].clear();
						this.dataPoints[index].addAll(inputDataArray);
						this.remapDataColumn(index, remappedIds);
						
						if (RUN_DATA_ORDER_VALIDATION)
						{
							throwIfDataColumnInWrongOrder(inputDataSource.pos, this.dataPoints[index]);
						}
						
						
						
						if (!dataChanged)
						{
							// check if the identical length data column hashes are the same
							// hashes need to be compared after the ID's have been remapped otherwise the ID's won't match even if the data is the same
							if (oldDataHash != this.dataPoints[index].hashCode())
							{
								// the hashes are different, something was changed
								dataChanged = true;
							}
						}
						
						this.columnGenerationSteps[index] = inputGenState;
						this.isEmpty = false;
					}
				}
			}
		}
		
		return dataChanged;
	}
	public boolean updateFromOneBelowDetailLevel(FullDataSourceV2 inputDataSource, int[] remappedIds)
	{
		if (inputDataSource.pos.getDetailLevel() + 1 != this.pos.getDetailLevel())
		{
			throw new IllegalArgumentException("Input data source must be exactly 1 detail level below this data source. Expected [" + (this.pos.getDetailLevel() - 1) + "], received [" + inputDataSource.pos.getDetailLevel() + "].");
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
				int recipientX = (x / 2) + recipientOffsetX;
				int recipientZ = (z / 2) + recipientOffsetZ;
				int recipientIndex = relativePosToIndex(recipientX, recipientZ);
				
				
				// world gen //
				byte inputGenStep = determineMinWorldGenStepForTwoByTwoColumn(inputDataSource.columnGenerationSteps, x, z);
				this.columnGenerationSteps[recipientIndex] = inputGenStep;
				
				
				
				// data points //
				LongArrayList mergedInputDataArray = mergeInputTwoByTwoDataColumn(inputDataSource, x, z);
				
				// check if the data changed
				if (this.dataPoints[recipientIndex] == null)
				{
					// no data was present previously
					dataChanged = true;
				}
				else if (this.dataPoints[recipientIndex].size() != mergedInputDataArray.size())
				{
					// data is present, but the size is different
					dataChanged = true;
				}
				
				int oldDataHash = 0;
				if (!dataChanged)
				{
					// some old data existed with the same length,
					// we'll have to compare the caches
					oldDataHash = this.dataPoints[recipientIndex].hashCode();
				}
				
				
				this.dataPoints[recipientIndex] = mergedInputDataArray;
				this.remapDataColumn(recipientIndex, remappedIds);
				
				if (RUN_DATA_ORDER_VALIDATION)
				{
					throwIfDataColumnInWrongOrder(inputDataSource.pos, this.dataPoints[recipientIndex]);
				}
				
				
				
				if (!dataChanged)
				{
					// check if the identical length data column hashes are the same
					// hashes need to be compared after the ID's have been remapped otherwise the ID's won't match even if the data is the same
					if (oldDataHash != this.dataPoints[recipientIndex].hashCode())
					{
						// the hashes are different, something was changed
						dataChanged = true;
					}
				}
				
				this.isEmpty = false;
			}
		}
		
		return dataChanged;
	}
	/** 
	 * The minimum value is used because we don't want to accidentally record that
	 * something was generated when it wasn't.
	 */
	private static byte determineMinWorldGenStepForTwoByTwoColumn(byte[] columnGenerationSteps, int relX, int relZ)
	{
		byte minWorldGenStepValue = Byte.MAX_VALUE;
		for (int x = 0; x < 2; x++)
		{
			for (int z = 0; z < 2; z++)
			{
				int index = relativePosToIndex(x + relX, z + relZ);
				byte worldGenStepValue = columnGenerationSteps[index];
				minWorldGenStepValue = (byte) Math.min(minWorldGenStepValue, worldGenStepValue);
			}
		}
		return minWorldGenStepValue;
	}
	private static LongArrayList mergeInputTwoByTwoDataColumn(FullDataSourceV2 inputDataSource, int x, int z)
	{
		LongArrayList newColumnList = new LongArrayList();
		
		// special numbers:
		// -2 = the column's height hasn't been determined yet
		// -1 = we've reached the end of the column
		int[] currentDatapointIndex = new int[] { -2, -2, -2, -2 };
		
		int lastId = 0;
		byte lastBlockLight = 0;
		byte lastSkyLight = 0;
		int height = 0;
		int minY = 0;
		
		
		// these arrays will be reused quite often, so re-using them helps reduce some GC pressure
		long[] datapointsForYSlice = new long[4];
		int[] mergeIds = new int[4];
		int[] mergeBlockLights = new int[4];
		int[] mergeSkyLights = new int[4];
		
		
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
			
			
			// scary double loop but, 
			// this will only ever loop 4 times, 
			// once for each of the 4 input columns
			Arrays.fill(datapointsForYSlice, 0L);
			int colIndex = 0;
			for (int inputX = x; inputX < x + 2; inputX++)
			{
				for (int inputZ = z; inputZ < z + 2; inputZ++, colIndex++)
				{
					// TODO throw an assertion if the column isn't in top-down order or just fix it...
					LongArrayList inputDataArray = inputDataSource.dataPoints[relativePosToIndex(inputX, inputZ)];
					if (inputDataArray == null || inputDataArray.size() == 0)
					{
						currentDatapointIndex[colIndex] = -1;
						continue;
					}
					
					// determine the last index (the lowest data point) for each column
					if (currentDatapointIndex[colIndex] == -2)
					{
						currentDatapointIndex[colIndex] = inputDataArray.size() - 1;
						
						if (RUN_DATA_ORDER_VALIDATION)
						{
							throwIfDataColumnInWrongOrder(inputDataSource.pos, inputDataArray);
						}
					}
					
					
					int dataPointIndex = currentDatapointIndex[colIndex];
					if (dataPointIndex == -1)
					{
						// went over the end 
						continue;
					}
					long datapoint = inputDataArray.getLong(dataPointIndex);
					
					int datapointMinY = FullDataPointUtilV2.getBottomY(datapoint);
					int numbOfBlocksTall = FullDataPointUtilV2.getHeight(datapoint);
					int datapointMaxY = (datapointMinY + numbOfBlocksTall);
					
					
					// check if y position is inside this datapoint
					if (blockY < datapointMinY)
					{
						// this y-slice is below this datapoint, nothing can be added
						continue;
					}
					else if (blockY >= datapointMaxY)
					{
						// this y-slice is above the current datapoint,
						// try the next data point
						
						int newDatapointIndex = currentDatapointIndex[colIndex] - 1;
						if (newDatapointIndex < 0)
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
			
			
			
			Arrays.fill(mergeIds, 0);
			Arrays.fill(mergeBlockLights, 0);
			Arrays.fill(mergeSkyLights, 0);
			for (int i = 0; i < 4; i++)
			{
				mergeIds[i] = FullDataPointUtilV2.getId(datapointsForYSlice[i]);
				mergeBlockLights[i] = FullDataPointUtilV2.getBlockLight(datapointsForYSlice[i]);
				mergeSkyLights[i] = FullDataPointUtilV2.getSkyLight(datapointsForYSlice[i]);
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
					newColumnList.add(FullDataPointUtilV2.encode(lastId, height, minY, lastBlockLight, lastSkyLight));
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
			newColumnList.add(FullDataPointUtilV2.encode(lastId, height, minY, lastBlockLight, lastSkyLight));
		}
		
		
		
		// flip the array if necessary
		// TODO why is this sometimes necessary? What did I (James) screw up that causes the mergedInputDataArray
		//  to sometimes be in a different order? Is it potentially related to what detail level is coming in?
		{
			long firstDataPoint = newColumnList.getLong(0);
			int firstBottomY = FullDataPointUtilV2.getBottomY(firstDataPoint);
			
			long lastDataPoint = newColumnList.getLong(newColumnList.size() - 1);
			int lastBottomY = FullDataPointUtilV2.getBottomY(lastDataPoint);
			
			if (firstBottomY < lastBottomY)
			{
				// reverse the array so index 0 is the highest,
				// this is necessary for later logic
				// source: https://stackoverflow.com/questions/2137755/how-do-i-reverse-an-int-array-in-java
				for(int i = 0; i < newColumnList.size() / 2; i++)
				{
					long temp = newColumnList.getLong(i);
					newColumnList.set(i, newColumnList.getLong(newColumnList.size() - i - 1));
					newColumnList.set(newColumnList.size() - i - 1, temp);
				}
			}
		}
		
		return newColumnList;
	}
	/**
	 * Only update the ID once it's been added to this data source.
	 * Updating the incoming data source will cause issues if it is applied 
	 * to anything else due to multiple remapping.
	 */
	private void remapDataColumn(int dataPointIndex, int[] remappedIds)
	{
		LongArrayList dataColumn = this.dataPoints[dataPointIndex];
		for (int i = 0; i < dataColumn.size(); i++)
		{
			dataColumn.set(i, FullDataPointUtilV2.remap(remappedIds, dataColumn.getLong(i)));
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
	
	/** 
	 * Usually this should just be used internally, but there may be instances
	 * where the raw data arrays are available without the data source object.
	 */
	public static int relativePosToIndex(int relX, int relZ) throws IndexOutOfBoundsException
	{ 
		if (relX < 0 || relZ < 0 ||
			relX > WIDTH || relZ > WIDTH)
		{
			throw new IndexOutOfBoundsException("Relative data source positions must be between [0] and ["+WIDTH+"] (inclusive) the relative pos: ["+relX+","+relZ+"] is outside of those boundaries.");
		}
		
		return (relX * WIDTH) + relZ; 
	}
	
	/** 
	 * Throws an exception if the given
	 * full data column array is in the wrong order
	 * IE if the first data point is the lowest and the last data point is the highest.
	 * Data columns should be in reverse order, IE the first data point should be the highest data point.
	 * 
	 * @see FullDataSourceV2#dataPoints
	 */
	public static void throwIfDataColumnInWrongOrder(DhSectionPos pos, LongArrayList dataArray) throws IllegalStateException
	{
		long firstDataPoint = dataArray.getLong(0);
		int firstBottomY = FullDataPointUtilV2.getBottomY(firstDataPoint);
		
		long lastDataPoint = dataArray.getLong(dataArray.size() - 1);
		int lastBottomY = FullDataPointUtilV2.getBottomY(lastDataPoint);
		
		if (firstBottomY < lastBottomY)
		{
			throw new IllegalStateException("Incorrect data point order at pos: "+pos+", first datapoint bottom Y ["+firstBottomY+"], last datapoint bottom Y ["+lastBottomY+"].");
		}
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
	
	public EDhApiWorldGenerationStep getWorldGenStepAtRelativePos(int relX, int relZ) 
	{
		int index = relativePosToIndex(relX, relZ);
		return EDhApiWorldGenerationStep.fromValue(this.columnGenerationSteps[index]); 
	}
	
	public void setSingleColumn(LongArrayList longArray, int relX, int relZ, EDhApiWorldGenerationStep worldGenStep)
	{
		int index = relativePosToIndex(relX, relZ);
		this.dataPoints[index] = longArray;
		this.columnGenerationSteps[index] =  worldGenStep.value;
		
		
		if (RUN_UPDATE_DEV_VALIDATION)
		{
			// validate the incoming ID's
			int maxValidId = this.mapping.getMaxValidId();
			for (int i = 0; i < longArray.size(); i++)
			{
				long dataPoint = longArray.getLong(i);
				int id = FullDataPointUtilV2.getId(dataPoint);
				if (id > maxValidId)
				{
					LodUtil.assertNotReach("Column set with higher than possible ID. ID [" + id + "], max valid ID [" + maxValidId + "].");
				}
			}
		}
	}
	
	
	
	//================//
	// base overrides //
	//================//
	
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
		if (!(obj instanceof FullDataSourceV2))
		{
			return false;
		}
		FullDataSourceV2 other = (FullDataSourceV2) obj;
		
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
	
	@Override
	public void close() throws Exception
	{
		returnPooledDataSource(this);
	}
	
	
	/** used when pooling data sources */
	private static final ArrayList<FullDataSourceV2> CACHED_SOURCES = new ArrayList<>();
	private static final ReentrantLock CACHE_LOCK = new ReentrantLock();
	
	
	/** @return an empty data source if non are cached */
	public static FullDataSourceV2 getPooledSource(DhSectionPos pos, boolean clearData)
	{
		try
		{
			CACHE_LOCK.lock();
			
			int index = CACHED_SOURCES.size() - 1;
			if (index == -1)
			{
				// no pooled sources exist
				return createEmpty(pos);
			}
			else
			{
				FullDataSourceV2 dataSource = CACHED_SOURCES.remove(index);
				dataSource.pos = pos;
				
				if (clearData)
				{
					dataSource.mapping.clear(pos);
					
					for (int i = 0; i < dataSource.dataPoints.length; i++)
					{
						if (dataSource.dataPoints[i] != null)
						{
							dataSource.dataPoints[i].clear();
						}
					}
					
					Arrays.fill(dataSource.columnGenerationSteps, (byte) 0);
				}
				
				return dataSource;
			}
		}
		finally
		{
			CACHE_LOCK.unlock();
		}
	}
	
	/**
	 * Doesn't have to be called, if a data source isn't returned, nothing will be leaked. 
	 * It just means a new source must be constructed next time {@link FullDataSourceV2#getPooledSource} is called.
	 */
	public static void returnPooledDataSource(FullDataSourceV2 dataSource)
	{
		if (dataSource == null)
		{
			return;
		}
		else if (CACHED_SOURCES.size() > 25)
		{
			return;
		}
		
		try
		{
			CACHE_LOCK.lock();
			CACHED_SOURCES.add(dataSource);
		}
		finally
		{
			CACHE_LOCK.unlock();
		}
	}
	
	
}
