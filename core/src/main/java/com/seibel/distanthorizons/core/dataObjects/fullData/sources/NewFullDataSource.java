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
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.SingleColumnFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder;
import com.seibel.distanthorizons.core.file.IDataSource;
import com.seibel.distanthorizons.core.file.fullDatafile.NewFullDataFileHandler;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.coreapi.ModInfo;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This data source contains every datapoint over its given {@link DhSectionPos}.
 *
 * TODO create a child object that extends AutoClosable
 *  that can be pooled so we don't have GC overhead 
 * 
 * @see FullDataPointUtil
 * @see CompleteFullDataSource
 */
public class NewFullDataSource implements IDataSource<IDhLevel>
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
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
	
	/** 
	 * stores how far each column has been generated should start with {@link EDhApiWorldGenerationStep#EMPTY}
	 * @see EDhApiWorldGenerationStep 
	 */
	public byte[] columnGenerationSteps;
	
	/** stored x/z, y */
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
		byte[] columnGenerationSteps = new byte[WIDTH * WIDTH];
		long[][] dataPoints = new long[WIDTH * WIDTH][];
		for (int x = 0; x < WIDTH; x++)
		{
			for (int z = 0; z < WIDTH; z++)
			{
				int index = relativePosToIndex(x, z);
				
				SingleColumnFullDataAccessor accessor = legacyData.get(x, z);
				
				if (accessor.doesColumnExist())
				{
					dataPoints[index] = accessor.getRaw();
					columnGenerationSteps[index] = legacyData.getWorldGenStep().value;
				}
			}
		}
		
		return NewFullDataSource.createWithData(legacyData.getSectionPos(), legacyData.getMapping(), dataPoints, columnGenerationSteps); 
	}
	
	
	
	//======//
	// data //
	//======//
	
	public SingleColumnFullDataAccessor get(int relX, int relZ) { return new SingleColumnFullDataAccessor(this.mapping, this.dataPoints, relativePosToIndex(relX, relZ)); }
	
	@Override
	public void update(NewFullDataSource inputDataSource, @Nullable IDhLevel level) { this.update(inputDataSource); }
	public void update(NewFullDataSource inputDataSource)
	{
		byte thisDetailLevel = this.pos.getDetailLevel();
		byte inputDetailLevel = inputDataSource.pos.getDetailLevel();
		
		
		// determine the mapping changes necessary for the input to map onto this datasource
		int[] remappedIds = this.mapping.mergeAndReturnRemappedEntityIds(inputDataSource.mapping);
		
		boolean dataChanged = false;
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
			// TODO what should happen here?
			
			// other detail levels aren't supported since it would be more difficult to maintain
			// and would lead to edge cases that don't necessarily need to be supported 
			// (IE what do you do when the input is smaller than a single datapoint in the receiving data source?)
			// instead it's better to just percolate the updates up
			//throw new UnsupportedOperationException("Unsupported data source update. Expected input detail level of ["+thisDetailLevel+"] or ["+(thisDetailLevel+1)+"], received detail level ["+inputDetailLevel+"].");
		}
		
		if (dataChanged && this.pos.getDetailLevel() < NewFullDataFileHandler.TOP_SECTION_DETAIL_LEVEL)
		{
			// mark that this data source should be applied to its parent
			this.applyToParent = true;
		}
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
						this.dataPoints[index] = new long[newDataArray.length];
						System.arraycopy(newDataArray, 0, this.dataPoints[index], 0, newDataArray.length);
						this.remapDataColumn(index, remappedIds);
						
						this.columnGenerationSteps[index] = inputGenState;
						
						dataChanged = true; // TODO contents of the arrays should be compared to prevent re-writing the same data
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
				int inputIndex = relativePosToIndex(x, z);

				long[] inputDataArray = inputDataSource.dataPoints[inputIndex];
				if (inputDataArray != null)
				{
					byte inputGenStep = inputDataSource.columnGenerationSteps[inputIndex];
					
					// TODO downsample instad of grabbing the column nearest to (-inf, -inf)
					int recipientX = (x / 2) + recipientOffsetX;
					int recipientZ = (z / 2) + recipientOffsetZ;
					int recipientIndex = relativePosToIndex(recipientX, recipientZ);
					
					this.columnGenerationSteps[recipientIndex] = inputGenStep;
					this.dataPoints[recipientIndex] = inputDataArray;
					this.remapDataColumn(recipientIndex, remappedIds);
					
					this.isEmpty = false;
					
					dataChanged = true; // TODO contents of the arrays should probably be compared or something
				}
			}
		}
		
		return dataChanged;
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
	
	
	
	//================//
	// helper methods //
	//================//
	
	// TODO make private, any external logic should go through a method, not interact with the arrays directly
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
		
		
		// validate the incoming ID's
		// shouldn't normally happen and can be disabled for release builds
		if (ModInfo.IS_DEV_BUILD)
		{
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
