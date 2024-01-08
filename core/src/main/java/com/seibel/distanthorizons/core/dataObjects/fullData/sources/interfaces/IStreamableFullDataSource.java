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

package com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.DataSourceDto;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;

import java.io.IOException;

/**
 * This interface holds the complete method list necessary for reading and writing a {@link IFullDataSource}
 * to and from data streams. <br><br>
 *
 * This interface's purpose is to reduce the chance of accidentally mismatching read/write operation data types or content by splitting
 * up each read/write method into small easy to understand chunks.
 *
 * @param <SummaryDataType> defines the object holding this data source's summary data, extends {@link IStreamableFullDataSource.FullDataSourceSummaryData}.
 * @param <DataContainerType> defines the object holding the data points, probably long[][] or long[][][].
 * {@link IStreamableFullDataSource#populateFromStream(DataSourceDto, DhDataInputStream, IDhLevel) populateFromStream}
 * for the full reasoning.
 */
public interface IStreamableFullDataSource<SummaryDataType extends IStreamableFullDataSource.FullDataSourceSummaryData, DataContainerType> extends IFullDataSource
{
	
	//=================//
	// stream handling // 
	//=================//
	
	/**
	 * Clears and then overwrites any data in this object with the data from the given file and stream.
	 * This is expected to be used with an existing {@link IStreamableFullDataSource} and can be used in place of a constructor to reuse an existing {@link IStreamableFullDataSource} object.
	 * 
	 * @see IStreamableFullDataSource#populateFromStream
	 */
	@Override
	default void repopulateFromStream(DataSourceDto dto, DhDataInputStream inputStream, IDhLevel level) throws IOException, InterruptedException
	{
		// clear/overwrite the old data
		this.resizeDataStructuresForRepopulation(dto.pos);
		this.getMapping().clear(dto.pos);
		
		// set the new data
		this.populateFromStream(dto, inputStream, level);
	}
	
	/**
	 * Clears and then overwrites any data in this object with the data from the given file and stream.
	 * This is expected to be used with an existing {@link IStreamableFullDataSource} and can be used in place of a constructor to reuse an existing {@link IStreamableFullDataSource} object.
	 *
	 * @see IStreamableFullDataSource#populateFromStream
	 */
	default void repopulateFromStream(DhSectionPos pos, DhDataInputStream inputStream, IDhLevel level) throws IOException, InterruptedException
	{
		// clear/overwrite the old data
		this.resizeDataStructuresForRepopulation(pos);
		this.getMapping().clear(pos);
		
		// set the new data
		this.populateFromStream(null, inputStream, level);
	}
	
	/**
	 * Overwrites any data in this object with the data from the given file and stream.
	 * This is expected to be used with an empty {@link IStreamableFullDataSource} and functions similar to a constructor.
	 */
	@Override
	default void populateFromStream(DataSourceDto dto, DhDataInputStream inputStream, IDhLevel level) throws IOException, InterruptedException
	{
		SummaryDataType summaryData = this.readSourceSummaryInfo(dto, inputStream, level);
		this.setSourceSummaryData(summaryData);
		
		
		DataContainerType dataPoints = this.readDataPoints(dto, summaryData.dataWidth, inputStream);
		if (dataPoints == null)
		{
			return;
		}
		this.setDataPoints(dataPoints);
		
		
		FullDataPointIdMap mapping = this.readIdMappings(dataPoints, inputStream, level.getLevelWrapper());
		this.setIdMapping(mapping);
		
	}
	
	@Override
	default void writeToStream(DhDataOutputStream outputStream, IDhLevel level) throws IOException
	{
		this.writeSourceSummaryInfo(level, outputStream);
		
		boolean hasData = this.writeDataPoints(outputStream);
		if (!hasData)
		{
			return;
		}
		
		this.writeIdMappings(outputStream);
	}
	
	
	
	/** Note: this should only be used if the data source is being reused. Normally data sources shouldn't change. */
	void resizeDataStructuresForRepopulation(DhSectionPos pos);
	
	/**
	 * Includes information about the source file that doesn't need to be saved in each data point. Like the source's size and y-level.
	 */
	void writeSourceSummaryInfo(IDhLevel level, DhDataOutputStream outputStream) throws IOException;
	/**
	 * Confirms that the given {@link DataSourceDto} is valid for this {@link IStreamableFullDataSource}. <br>
	 * This specifically checks any fields that should be set when the {@link IStreamableFullDataSource} was first constructed.
	 *
	 * @throws IOException if the {@link DataSourceDto} isn't valid for this object.
	 */
	SummaryDataType readSourceSummaryInfo(DataSourceDto dto, DhDataInputStream inputStream, IDhLevel level) throws IOException;
	void setSourceSummaryData(SummaryDataType summaryData);
	
	
	/** @return true if any data points were present and written, false if this object was empty */
	boolean writeDataPoints(DhDataOutputStream outputStream) throws IOException;
	/** @return null if no data points were present */
	DataContainerType readDataPoints(DataSourceDto dto, int width, DhDataInputStream inputStream) throws IOException;
	void setDataPoints(DataContainerType dataPoints);
	
	
	void writeIdMappings(DhDataOutputStream outputStream) throws IOException;
	FullDataPointIdMap readIdMappings(DataContainerType dataPoints, DhDataInputStream inputStream, ILevelWrapper levelWrapper) throws IOException, InterruptedException;
	void setIdMapping(FullDataPointIdMap mappings);
	
	
	
	//================//
	// helper classes //
	//================//
	
	/**
	 * This holds information that is relevant to the entire source and isn't stored in the data points. <br>
	 * Example: minimum height, detail level, source type, etc.
	 */
	class FullDataSourceSummaryData
	{
		public final int dataWidth;
		public EDhApiWorldGenerationStep worldGenStep;
		
		
		public FullDataSourceSummaryData(int dataWidth, EDhApiWorldGenerationStep worldGenStep)
		{
			this.dataWidth = dataWidth;
			this.worldGenStep = worldGenStep;
		}
		
	}
	
}
