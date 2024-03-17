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

package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV1;
import com.seibel.distanthorizons.core.file.AbstractLegacyDataSourceHandler;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.repo.AbstractLegacyDataSourceRepo;
import com.seibel.distanthorizons.core.sql.repo.LegacyFullDataRepo;
import com.seibel.distanthorizons.core.sql.dto.LegacyDataSourceDTO;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;

public class FullDataSourceProviderV1 extends AbstractLegacyDataSourceHandler<FullDataSourceV1, IDhLevel> 
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public FullDataSourceProviderV1(IDhLevel level, AbstractSaveStructure saveStructure, @Nullable File saveDirOverride) 
	{
		super(level, saveStructure, saveDirOverride);
	}
	
	
	
	//====================//
	// Abstract overrides //
	//====================//
	
	@Override
	protected AbstractLegacyDataSourceRepo createRepo()
	{
		try
		{
			return new LegacyFullDataRepo("jdbc:sqlite", this.saveDir.getPath() + "/" + AbstractSaveStructure.DATABASE_NAME);
		}
		catch (SQLException e)
		{
			// should only happen if there is an issue with the database (it's locked or can't be created if missing) 
			// or the database update failed
			throw new RuntimeException(e);
		}
	}
	
	@Override
	protected FullDataSourceV1 createDataSourceFromDto(LegacyDataSourceDTO dto) throws InterruptedException, IOException
	{
		FullDataSourceV1 dataSource = FullDataSourceV1.createEmpty(dto.pos);
		dataSource.populateFromStream(dto, dto.getInputStream(), this.level);
		return dataSource;
	}
	/** Creates a new data source using any DTOs already present in the database. */
	@Deprecated
	@Override
	protected FullDataSourceV1 createNewDataSourceFromExistingDtos(DhSectionPos pos) { return null; }
	
	@Deprecated
	@Override
	protected FullDataSourceV1 makeEmptyDataSource(DhSectionPos pos) { return null; }
	
	
	
	//===================//
	// extension methods //
	//===================//
	
	@Deprecated
	@Override
	public void writeDataSourceToFile(FullDataSourceV1 fullDataSource) throws IOException
	{ throw new UnsupportedOperationException("Deprecated"); }
	
	
	
	//===========//
	// migration //
	//===========//
	
	public int getDataSourceMigrationCount() { return ((LegacyFullDataRepo) this.repo).getMigrationCount(); }
	
	public ArrayList<FullDataSourceV1> getDataSourcesToMigrate(int limit)
	{
		ArrayList<FullDataSourceV1> dataSourceList = new ArrayList<>();
		
		ArrayList<DhSectionPos> migrationPosList = ((LegacyFullDataRepo) this.repo).getPositionsToMigrate(limit);
		for (int i = 0; i < migrationPosList.size(); i++)
		{
			DhSectionPos pos = migrationPosList.get(i);
			FullDataSourceV1 dataSource = this.get(pos);
			if (dataSource != null)
			{
				dataSourceList.add(dataSource);
			}
		}
		
		return dataSourceList;
	}
	
	public void markMigrationFailed(DhSectionPos pos) { ((LegacyFullDataRepo) this.repo).markMigrationFailed(pos); }
	
	
}
