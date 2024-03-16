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

package com.seibel.distanthorizons.core.sql.dto;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.NewFullDataSource;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Adler32;
import java.util.zip.CheckedOutputStream;

/** handles storing {@link NewFullDataSource}'s in the database. */
public class NewFullDataSourceDTO implements IBaseDTO<DhSectionPos>
{
	public DhSectionPos pos;
	
	public int levelMinY;
	
	/** only for the data array */
	public int dataChecksum;
	
	public byte[] dataByteArray;
	
	/** @see EDhApiWorldGenerationStep */
	public byte[] columnGenStepByteArray;
	
	public byte[] mappingByteArray;
	
	public byte dataFormatVersion;
	public EDhApiDataCompressionMode compressionModeEnum;
	
	public boolean applyToParent;
	
	public long lastModifiedUnixDateTime;
	public long createdUnixDateTime;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public static NewFullDataSourceDTO CreateFromDataSource(NewFullDataSource dataSource, EDhApiDataCompressionMode compressionModeEnum) throws IOException
	{
		CheckedByteArray checkedDataPointArray = writeDataSourceDataArrayToBlob(dataSource.dataPoints, compressionModeEnum);
		byte[] mappingByteArray = writeDataMappingToBlob(dataSource.getMapping(), compressionModeEnum);
		
		return new NewFullDataSourceDTO(
				dataSource.getSectionPos(), 
				checkedDataPointArray.checksum, dataSource.columnGenerationSteps, NewFullDataSource.DATA_FORMAT_VERSION, compressionModeEnum, checkedDataPointArray.byteArray,
				dataSource.lastModifiedUnixDateTime, dataSource.createdUnixDateTime,
				mappingByteArray, dataSource.applyToParent, 
				dataSource.levelMinY
		);
	}
	public NewFullDataSourceDTO(
			DhSectionPos pos, 
			int dataChecksum, byte[] columnGenStepByteArray, byte dataFormatVersion, EDhApiDataCompressionMode compressionModeEnum, byte[] dataByteArray,
			long lastModifiedUnixDateTime, long createdUnixDateTime,
			byte[] mappingByteArray, boolean applyToParent,
			int levelMinY)
	{
		this.pos = pos;
		this.dataChecksum = dataChecksum;
		this.columnGenStepByteArray = columnGenStepByteArray;
		
		this.dataFormatVersion = dataFormatVersion;
		this.compressionModeEnum = compressionModeEnum;
		
		this.dataByteArray = dataByteArray;
		this.mappingByteArray = mappingByteArray;
		
		this.applyToParent = applyToParent;
		
		this.lastModifiedUnixDateTime = lastModifiedUnixDateTime;
		this.createdUnixDateTime = createdUnixDateTime;
		
		this.levelMinY = levelMinY;
	}
	
	
	
	//========================//
	// data source population //
	//========================//
	
	public NewFullDataSource createDataSource(@NotNull ILevelWrapper levelWrapper) throws IOException, InterruptedException 
	{ return this.populateDataSource(NewFullDataSource.createEmpty(this.pos), levelWrapper); }
	
	public NewFullDataSource populateDataSource(NewFullDataSource dataSource, @NotNull ILevelWrapper levelWrapper) throws IOException, InterruptedException 
	{ return this.internalPopulateDataSource(dataSource, levelWrapper, false); }
	
	/** 
	 * May be missing one or more data fields. <br>
	 * Designed to be used without access to Minecraft or any supporting objects. 
	 */
	public NewFullDataSource createUnitTestDataSource() throws IOException, InterruptedException 
	{ return this.internalPopulateDataSource(NewFullDataSource.createEmpty(this.pos), null, true); }
	
	private NewFullDataSource internalPopulateDataSource(NewFullDataSource dataSource, ILevelWrapper levelWrapper, boolean unitTest) throws IOException, InterruptedException
	{
		if (NewFullDataSource.DATA_FORMAT_VERSION != this.dataFormatVersion)
		{
			throw new IllegalStateException("There should only be one data format right now anyway.");
		}
		
		dataSource.columnGenerationSteps = this.columnGenStepByteArray;
		dataSource.dataPoints = readBlobToDataSourceDataArray(this.dataByteArray, this.compressionModeEnum);
		
		dataSource.getMapping().clear(dataSource.getSectionPos());
		// should only be null when used in a unit test
		if (!unitTest)
		{
			if (levelWrapper == null)
			{
				throw new NullPointerException("No level wrapper present, unable to deserialize data map. This should only be used for unit tests.");
			}
			
			dataSource.getMapping().mergeAndReturnRemappedEntityIds(readBlobToDataMapping(this.mappingByteArray, dataSource.getSectionPos(), levelWrapper,  this.compressionModeEnum));
		}
		
		dataSource.lastModifiedUnixDateTime = this.lastModifiedUnixDateTime;
		dataSource.createdUnixDateTime = this.createdUnixDateTime;
		
		dataSource.levelMinY = this.levelMinY;
		
		dataSource.markNotEmpty();
		
		return dataSource;
	}
	
	
	
	//=================//
	// (de)serializing //
	//=================//
	
	private static CheckedByteArray writeDataSourceDataArrayToBlob(long[][] dataArray, EDhApiDataCompressionMode compressionModeEnum) throws IOException
	{
		// write the outputs to a stream to prep for writing to the database
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		
		// the order of these streams is important, otherwise the checksum won't be calculated
		CheckedOutputStream checkedOut = new CheckedOutputStream(byteArrayOutputStream, new Adler32());
		// normally a DhStream should be the topmost stream to prevent closing the stream accidentally, 
		// but since this stream will be closed immediately after writing anyway, it won't be an issue
		DhDataOutputStream compressedOut = new DhDataOutputStream(checkedOut, compressionModeEnum);
		
		
		// write the data
		int dataArrayLength = NewFullDataSource.WIDTH * NewFullDataSource.WIDTH;
		for (int xz = 0; xz < dataArrayLength; xz++)
		{
			long[] dataColumn = dataArray[xz];
			
			// write column length
			int columnLength = (dataColumn != null) ? dataColumn.length : 0;
			compressedOut.writeInt(columnLength); /// TODO
			
			// write column data (will be skipped if no data was present)
			for (int y = 0; y < columnLength; y++)
			{
				compressedOut.writeLong(dataColumn[y]);
			}
		}
		
		
		// generate the checksum
		compressedOut.flush();
		int checksum = (int) checkedOut.getChecksum().getValue();
		byteArrayOutputStream.close();
		
		return new CheckedByteArray(checksum, byteArrayOutputStream.toByteArray());
	}
	private static long[][] readBlobToDataSourceDataArray(byte[] dataByteArray, EDhApiDataCompressionMode compressionModeEnum) throws IOException
	{
		ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(dataByteArray);
		DhDataInputStream compressedIn = new DhDataInputStream(byteArrayInputStream, compressionModeEnum);
		
		
		// read the data
		int dataArrayLength = NewFullDataSource.WIDTH * NewFullDataSource.WIDTH;
		long[][] dataArray = new long[dataArrayLength][];
		for (int xz = 0; xz < dataArray.length; xz++)
		{
			// read the column length
			int dataColumnLength = compressedIn.readInt(); // separate variables are used for debugging and in case validation wants to be added later 
			long[] dataColumn = new long[dataColumnLength];
			
			// read column data (will be skipped if no data was present)
			for (int y = 0; y < dataColumnLength; y++)
			{
				long dataPoint = compressedIn.readLong();
				dataColumn[y] = dataPoint;
			}
			
			dataArray[xz] = dataColumn;
		}
		
		
		return dataArray;
	}
	
	
	private static byte[] writeDataMappingToBlob(FullDataPointIdMap mapping, EDhApiDataCompressionMode compressionModeEnum) throws IOException
	{
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		DhDataOutputStream compressedOut = new DhDataOutputStream(byteArrayOutputStream, compressionModeEnum);
		
		mapping.serialize(compressedOut);
		
		compressedOut.flush();
		byteArrayOutputStream.close();
		
		return byteArrayOutputStream.toByteArray();
	}
	private static FullDataPointIdMap readBlobToDataMapping(byte[] dataByteArray, DhSectionPos pos, @NotNull ILevelWrapper levelWrapper, EDhApiDataCompressionMode compressionModeEnum) throws IOException, InterruptedException
	{
		ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(dataByteArray);
		DhDataInputStream compressedIn = new DhDataInputStream(byteArrayInputStream, compressionModeEnum);
		
		FullDataPointIdMap mapping = FullDataPointIdMap.deserialize(compressedIn, pos, levelWrapper);
		return mapping;
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override 
	public DhSectionPos getKey() { return this.pos; }
	
	
	
	//================//
	// helper classes //
	//================//
	
	private static class CheckedByteArray
	{
		public final int checksum;
		public final byte[] byteArray;
		
		public CheckedByteArray(int checksum, byte[] byteArray)
		{
			this.checksum = checksum;
			this.byteArray = byteArray;
		}
	}
	
}
