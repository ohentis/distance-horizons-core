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

package com.seibel.distanthorizons.core.file.renderfile;

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSourceLoader;
import com.seibel.distanthorizons.core.dataObjects.transformers.FullDataToRenderDataTransformer;
import com.seibel.distanthorizons.core.file.AbstractDataSourceHandler;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.file.fullDatafile.IFullDataSourceProvider;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.sql.DataSourceDto;
import com.seibel.distanthorizons.core.sql.RenderDataRepo;
import com.seibel.distanthorizons.core.util.threading.ThreadPools;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

public class RenderSourceFileHandler extends AbstractDataSourceHandler<ColumnRenderSource, IDhClientLevel> implements IRenderSourceProvider
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private final F3Screen.NestedMessage threadPoolMsg;
	
	private final IFullDataSourceProvider fullDataSourceProvider;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public RenderSourceFileHandler(IFullDataSourceProvider sourceProvider, IDhClientLevel clientLevel, AbstractSaveStructure saveStructure)
	{
		super(clientLevel, saveStructure, createRepo(clientLevel, saveStructure));
		
		this.fullDataSourceProvider = sourceProvider;
		this.threadPoolMsg = new F3Screen.NestedMessage(this::f3Log);
	}
	private static RenderDataRepo createRepo(IDhClientLevel clientLevel, AbstractSaveStructure saveStructure)
	{
		File saveDir = saveStructure.getRenderCacheFolder(clientLevel.getLevelWrapper());
		
		try
		{
			return new RenderDataRepo("jdbc:sqlite", saveDir.getPath() + "/" + AbstractSaveStructure.DATABASE_NAME);
		}
		catch (SQLException e)
		{
			// should only happen if there is an issue with the database (it's locked or can't be created if missing) 
			// or the database update failed
			throw new RuntimeException(e);
		}
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override
	public ColumnRenderSource get(DhSectionPos pos)
	{
		// call the full data provider to make sure the full data is up to date
		// and any necessary world generation has been queued/completed
		this.fullDataSourceProvider.get(pos);
		
		return super.get(pos);
	}
	
	
	//====================//
	// Abstract overrides //
	//====================//
	
	@Override 
	protected ColumnRenderSource createDataSourceFromDto(DataSourceDto dto) throws InterruptedException, IOException
	{ return ColumnRenderSourceLoader.INSTANCE.loadRenderSource(dto, dto.getInputStream(), this.level); }
	@Override 
	protected ColumnRenderSource createNewDataSourceFromExistingDtos(DhSectionPos pos) 
	{
		ColumnRenderSource renderDataSource;
		
		IFullDataSource fullDataSource = this.fullDataSourceProvider.get(pos);
		if (fullDataSource != null)
		{
			renderDataSource = FullDataToRenderDataTransformer.transformFullDataToRenderSource(fullDataSource, this.level);
		}
		else
		{
			renderDataSource = this.makeEmptyDataSource(pos);
		}
		return renderDataSource;
	}
	
	@Override 
	protected ColumnRenderSource makeEmptyDataSource(DhSectionPos pos) 
	{ return ColumnRenderSource.createEmptyRenderSource(pos); }
	
	
	
	
	//=========//
	// F3 menu //
	//=========//
	
	/** Returns what should be displayed in Minecraft's F3 debug menu */
	private String[] f3Log()
	{
		ThreadPoolExecutor executor = ThreadPools.getFileHandlerExecutor();
		String queueSize = (executor != null) ? executor.getQueue().size()+"" : "-";
		String completedTaskSize = (executor != null) ? executor.getCompletedTaskCount()+"" : "-";
		
		ArrayList<String> lines = new ArrayList<>();
		lines.add("File Handler [" + this.level.getLevelWrapper().getDimensionType().getDimensionName() + "]");
		lines.add("  Thread pool tasks: " + queueSize + " (completed: " + completedTaskSize + ")");
		lines.add("  Unsaved render sources: " + this.unsavedDataSourceBySectionPos.size());
		lines.add("  Unsaved data sources: " + this.fullDataSourceProvider.getUnsavedDataSourceCount());
		
		return lines.toArray(new String[0]);
	}
	
	@Override
	public CompletableFuture<Void> updateDataSourcesWithChunkDataAsync(ChunkSizedFullDataAccessor chunkDataView)
	{
		return CompletableFuture.allOf(
			super.updateDataSourcesWithChunkDataAsync(chunkDataView),
			this.fullDataSourceProvider.updateDataSourcesWithChunkDataAsync(chunkDataView)		
		);
	}
	
	
	//=====================//
	// shutdown / clearing //
	//=====================//
	
	public void close()
	{
		super.close();
		this.threadPoolMsg.close();
	}
	
	public void deleteRenderCache() { this.repo.deleteAll(); }
	
}
