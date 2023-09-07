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

package com.seibel.distanthorizons.core.file.metaData;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Contains and represents the meta information ({@link DhSectionPos}, {@link BaseMetaData#dataLevel}, etc.)
 * stored at the beginning of files that use the {@link AbstractMetaDataContainerFile}. <Br>
 * Which, as of the time of writing, includes: {@link IFullDataSource} and {@link ColumnRenderSource} files.
 */
public class BaseMetaData
{
	public DhSectionPos pos;
	public int checksum;
	public AtomicLong dataVersion = new AtomicLong(Long.MAX_VALUE);
	public byte dataLevel; // TODO what does this represent?
	public EDhApiWorldGenerationStep worldGenStep;
	
	// Loader stuff //
	/** indicates what data is held in this file, this is generally a hash of the data's name */
	public long dataTypeId;
	public byte binaryDataFormatVersion;
	
	
	
	public BaseMetaData(DhSectionPos pos, int checksum, byte dataLevel, EDhApiWorldGenerationStep worldGenStep, long dataTypeId, byte binaryDataFormatVersion, long dataVersion)
	{
		this.pos = pos;
		this.checksum = checksum;
		this.dataVersion = new AtomicLong(dataVersion);
		this.dataLevel = dataLevel;
		this.worldGenStep = worldGenStep;
		
		this.dataTypeId = dataTypeId;
		this.binaryDataFormatVersion = binaryDataFormatVersion;
	}
	
}
