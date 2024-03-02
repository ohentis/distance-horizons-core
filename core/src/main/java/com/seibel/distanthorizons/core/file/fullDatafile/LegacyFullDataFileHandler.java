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

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.file.AbstractLegacyDataSourceHandler;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.sql.repo.AbstractLegacyDataSourceRepo;
import com.seibel.distanthorizons.core.sql.repo.FullDataRepo;
import com.seibel.distanthorizons.core.sql.dto.LegacyDataSourceDTO;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

public class LegacyFullDataFileHandler 
		extends AbstractLegacyDataSourceHandler<CompleteFullDataSource, IDhLevel> 
		implements IDebugRenderable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public LegacyFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure) { this(level, saveStructure, null); }
	public LegacyFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure, @Nullable File saveDirOverride) 
	{
		super(level, saveStructure, saveDirOverride);
		
		DebugRenderer.register(this, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue);
	}
	
	
	
	//====================//
	// Abstract overrides //
	//====================//
	
	@Override
	protected AbstractLegacyDataSourceRepo createRepo()
	{
		try
		{
			return new FullDataRepo("jdbc:sqlite", this.saveDir.getPath() + "/" + AbstractSaveStructure.DATABASE_NAME);
		}
		catch (SQLException e)
		{
			// should only happen if there is an issue with the database (it's locked or can't be created if missing) 
			// or the database update failed
			throw new RuntimeException(e);
		}
	}
	
	@Override
	protected CompleteFullDataSource createDataSourceFromDto(LegacyDataSourceDTO dto) throws InterruptedException, IOException
	{
		CompleteFullDataSource dataSource = CompleteFullDataSource.createEmpty(dto.pos);
		dataSource.populateFromStream(dto, dto.getInputStream(), this.level);
		return dataSource;
	}
	/** Creates a new data source using any DTOs already present in the database. */
	@Deprecated
	@Override
	protected CompleteFullDataSource createNewDataSourceFromExistingDtos(DhSectionPos pos)
	{
		throw new UnsupportedOperationException("Deprecated");
	}
	
	
	@Deprecated
	@Override
	protected CompleteFullDataSource makeEmptyDataSource(DhSectionPos pos)
	{
		throw new UnsupportedOperationException("Deprecated");
	}
	
	
	
	//===================//
	// extension methods //
	//===================//
	
	@Deprecated
	@Override
	public void writeDataSourceToFile(CompleteFullDataSource fullDataSource) throws IOException
	{
		throw new UnsupportedOperationException("Deprecated");
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override
	public void debugRender(DebugRenderer renderer)
	{
		this.saveTimerTasksBySectionPos.keySet()
				.forEach((pos) -> { renderer.renderBox(new DebugRenderer.Box(pos, -32f, 128f, 0.15f, Color.cyan)); });
		
	}
	
	
}
