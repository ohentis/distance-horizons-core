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

package com.seibel.distanthorizons.core.sql;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/** handles storing both {@link IFullDataSource}'s and {@link ColumnRenderSource}'s in the database. */
public class DataSourceDto implements IBaseDTO
{
	public DhSectionPos pos;
	public int checksum;
	/** @deprecated the database now has a last modified date time that should be used instead */
	@Deprecated
	public AtomicLong dataVersion = new AtomicLong(Long.MAX_VALUE);
	public byte dataDetailLevel;
	public EDhApiWorldGenerationStep worldGenStep;
	
	// Loader stuff //
	/** indicates what data is held in this file, this is generally the data's name */
	public String dataType;
	public byte binaryDataFormatVersion;
	
	public final byte[] dataArray;
	
	
	public DataSourceDto(DhSectionPos pos, int checksum, byte dataDetailLevel, EDhApiWorldGenerationStep worldGenStep, String dataType, byte binaryDataFormatVersion, byte[] dataArray)
	{
		this.pos = pos;
		this.checksum = checksum;
		this.dataDetailLevel = dataDetailLevel;
		this.worldGenStep = worldGenStep;
		
		this.dataType = dataType;
		this.binaryDataFormatVersion = binaryDataFormatVersion;
		
		this.dataArray = dataArray;
	}
	
	
	@Override
	public String getPrimaryKeyString() { return this.pos.serialize(); }
	
	/** @return a stream for the data contained in this DTO. */
	public DhDataInputStream getInputStream() throws IOException
	{
		InputStream inputStream = new ByteArrayInputStream(this.dataArray);
		DhDataInputStream compressedStream = new DhDataInputStream(inputStream);
		return compressedStream;
	}
	
}
