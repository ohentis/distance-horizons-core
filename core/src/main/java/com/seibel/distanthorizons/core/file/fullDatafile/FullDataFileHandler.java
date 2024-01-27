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
import com.seibel.distanthorizons.core.dataObjects.fullData.loader.AbstractFullDataSourceLoader;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.HighDetailIncompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.LowDetailIncompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IIncompleteFullDataSource;
import com.seibel.distanthorizons.core.file.AbstractDataSourceHandler;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.sql.AbstractDataSourceRepo;
import com.seibel.distanthorizons.core.sql.FullDataRepo;
import com.seibel.distanthorizons.core.sql.DataSourceDto;
import com.seibel.distanthorizons.core.sql.FullDataRepo;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class FullDataFileHandler extends AbstractDataSourceHandler<IFullDataSource, IDhLevel> implements IFullDataSourceProvider
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();

	
	public Map<DhSectionPos, Integer> getLoadStates(Iterable<DhSectionPos> posList)
	{
		HashMap<DhSectionPos, Integer> map = new HashMap<>();
		for (DhSectionPos pos : posList)
		{
			map.put(pos,
					this.unsavedDataSourceBySectionPos.containsKey(pos) ? 3 // Loaded
							: this.fileExists(pos) ? 2                      // Unloaded
							: 1);                                           // Not generated
		}
		return map;
	}
	public boolean fileExists(DhSectionPos pos) { return this.repo.existsWithPrimaryKey(pos.serialize()); }
	
	
	//=============//
	// constructor //
	//=============//
	
	public FullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure) { this(level, saveStructure, null); }
	public FullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure, @Nullable File saveDirOverride) { super(level, saveStructure, saveDirOverride); }
	
	
	
	//====================//
	// Abstract overrides //
	//====================//
	
	@Override
	protected AbstractDataSourceRepo createRepo()
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
	protected IFullDataSource createDataSourceFromDto(DataSourceDto dto) throws InterruptedException, IOException
	{
		AbstractFullDataSourceLoader loader = AbstractFullDataSourceLoader.getLoader(dto.dataType, dto.binaryDataFormatVersion);
		IFullDataSource dataSource = loader.loadDataSource(dto, this.level);
		return dataSource;
	}
	/** Creates a new data source using any DTOs already present in the database. */
	@Override
	protected IFullDataSource createNewDataSourceFromExistingDtos(DhSectionPos pos)
	{
		IIncompleteFullDataSource newFullDataSource = this.makeEmptyDataSource(pos);
		
		
		boolean showFullDataFileSampling = Config.Client.Advanced.Debugging.DebugWireframe.showFullDataFileStatus.get();
		if (showFullDataFileSampling)
		{
			DebugRenderer.makeParticle(new DebugRenderer.BoxParticle(
					new DebugRenderer.Box(newFullDataSource.getSectionPos(), 64f, 72f, 0.03f, Color.MAGENTA),
					0.2, 32f));
		}
		
		
		// get all non-empty sections to sample from
		ArrayList<DhSectionPos> samplePosList = new ArrayList<>();
		ArrayList<DhSectionPos> possibleChildList = new ArrayList<>();
		pos.forEachChild((childPos) ->
		{
			if (childPos.getDetailLevel() >= this.minDetailLevel)
			{
				possibleChildList.add(childPos);
			}
		});
		while (possibleChildList.size() != 0)
		{
			DhSectionPos possiblePos = possibleChildList.remove(possibleChildList.size()-1);
			if (this.repo.existsWithPrimaryKey(possiblePos.serialize()))
			{
				samplePosList.add(possiblePos);
			}
			else
			{
				possiblePos.forEachChild((childPos) ->
				{
					if (childPos.getDetailLevel() >= this.minDetailLevel)
					{
						possibleChildList.add(childPos);
					}
				});
			}
		}
		
		
		// read in the existing data
		for (int i = 0; i < samplePosList.size(); i++)
		{
			DhSectionPos samplePos = samplePosList.get(i);
			IFullDataSource sampleDataSource = this.get(samplePos);
			if (sampleDataSource == null)
			{
				// no file was found, this is unexpected, but can be ignored
				continue;
			}
			
			if (showFullDataFileSampling)
			{
				DebugRenderer.makeParticle(new DebugRenderer.BoxParticle(
						new DebugRenderer.Box(newFullDataSource.getSectionPos(), 64f, 72f, 0.03f, Color.MAGENTA.darker()),
						0.2, 32f));
			}
			
			try
			{
				newFullDataSource.sampleFrom(sampleDataSource);
			}
			catch (Exception e)
			{
				LOGGER.warn("Unable to sample "+sampleDataSource.getSectionPos()+" into "+newFullDataSource.getSectionPos(), e);
			}
		}
		
		
		// promotion may happen if all children are fully populated
		return newFullDataSource.tryPromotingToCompleteDataSource();
	}
	
	@Override
	protected IIncompleteFullDataSource makeEmptyDataSource(DhSectionPos pos)
	{
		return pos.getDetailLevel() <= HighDetailIncompleteFullDataSource.MAX_SECTION_DETAIL ?
				HighDetailIncompleteFullDataSource.createEmpty(pos) :
				LowDetailIncompleteFullDataSource.createEmpty(pos);
	}
	
	
	
	//===================//
	// extension methods //
	//===================//
	
	@Override
	public void writeDataSourceToFile(IFullDataSource fullDataSource) throws IOException
	{
		// doing this here guarantees that all changes are caught and promoted
		if (fullDataSource instanceof IIncompleteFullDataSource)
		{
			fullDataSource = ((IIncompleteFullDataSource) fullDataSource).tryPromotingToCompleteDataSource();
		}
		
		super.writeDataSourceToFile(fullDataSource);
		
		// save has completed
		boolean showFullDataFileStatus = Config.Client.Advanced.Debugging.DebugWireframe.showFullDataFileStatus.get();
		if (showFullDataFileStatus)
		{
			DebugRenderer.makeParticle(new DebugRenderer.BoxParticle(
					new DebugRenderer.Box(fullDataSource.getSectionPos(), 64f, 70f, 0.02f, Color.YELLOW),
					0.2, 16f));
		}	
	}
	
	@Override
	public int getUnsavedDataSourceCount() { return this.unsavedDataSourceBySectionPos.size(); }
	
	
}
