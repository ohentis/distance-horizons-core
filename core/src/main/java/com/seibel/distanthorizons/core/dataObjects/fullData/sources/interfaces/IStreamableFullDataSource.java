package com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.FullDataArrayAccessor;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataMetaFile;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import com.seibel.distanthorizons.core.util.objects.dataStreams.*;
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
 * @apiNote James would've preferred to have this as an abstract class,
 * however that is impossible. See the apiNote in
 * {@link IStreamableFullDataSource#populateFromStream(FullDataMetaFile, DhDataInputStream, IDhLevel) populateFromStream}
 * for the full reasoning.
 */
public interface IStreamableFullDataSource<SummaryDataType extends IStreamableFullDataSource.FullDataSourceSummaryData, DataContainerType> extends IFullDataSource
{
	
	//=================//
	// stream handling // 
	//=================//
	
	/**
	 * Overwrites any data in this object with the data from the given file and stream.
	 * This is expected to be used with an empty {@link IStreamableFullDataSource} and functions similar to a constructor.
	 *
	 * @apiNote James would've preferred that {@link IStreamableFullDataSource} was an abstract class,
	 * so this could've been a constructor.
	 * However, several inheritors of this interface already extend {@link FullDataArrayAccessor}, making that impossible.
	 */
	default void populateFromStream(FullDataMetaFile dataFile, DhDataInputStream inputStream, IDhLevel level) throws IOException, InterruptedException
	{
		SummaryDataType summaryData = this.readSourceSummaryInfo(dataFile, inputStream, level);
		this.setSourceSummaryData(summaryData);
		
		
		DataContainerType dataPoints = this.readDataPoints(dataFile, summaryData.dataWidth, inputStream);
		if (dataPoints == null)
		{
			return;
		}
		this.setDataPoints(dataPoints);
		
		
		FullDataPointIdMap mapping = this.readIdMappings(dataPoints, inputStream, level.getLevelWrapper());
		this.setIdMapping(mapping);
		
	}
	
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
	
	
	
	/**
	 * Includes information about the source file that doesn't need to be saved in each data point. Like the source's size and y-level.
	 */
	void writeSourceSummaryInfo(IDhLevel level, DhDataOutputStream outputStream) throws IOException;
	/**
	 * Confirms that the given {@link FullDataMetaFile} is valid for this {@link IStreamableFullDataSource}. <br>
	 * This specifically checks any fields that should be set when the {@link IStreamableFullDataSource} was first constructed.
	 *
	 * @throws IOException if the {@link FullDataMetaFile} isn't valid for this object.
	 */
	SummaryDataType readSourceSummaryInfo(FullDataMetaFile dataFile, DhDataInputStream inputStream, IDhLevel level) throws IOException;
	void setSourceSummaryData(SummaryDataType summaryData);
	
	
	/** @return true if any data points were present and written, false if this object was empty */
	boolean writeDataPoints(DhDataOutputStream outputStream) throws IOException;
	/** @return null if no data points were present */
	DataContainerType readDataPoints(FullDataMetaFile dataFile, int width, DhDataInputStream inputStream) throws IOException;
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
