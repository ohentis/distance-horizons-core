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

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.NewFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSourceLoader;
import com.seibel.distanthorizons.core.dataObjects.transformers.FullDataToRenderDataTransformer;
import com.seibel.distanthorizons.core.file.AbstractLegacyDataSourceHandler;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.file.fullDatafile.IFullDataSourceProvider;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.sql.repo.AbstractLegacyDataSourceRepo;
import com.seibel.distanthorizons.core.sql.dto.LegacyDataSourceDTO;
import com.seibel.distanthorizons.core.sql.repo.RenderDataRepo;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

public class RenderSourceFileHandler extends AbstractLegacyDataSourceHandler<ColumnRenderSource, IDhClientLevel> implements IRenderSourceProvider
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private final F3Screen.NestedMessage threadPoolMsg;
	
	public final IFullDataSourceProvider fullDataSourceProvider;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public RenderSourceFileHandler(IFullDataSourceProvider sourceProvider, IDhClientLevel clientLevel, AbstractSaveStructure saveStructure)
	{
		super(clientLevel, saveStructure);
		
		this.fullDataSourceProvider = sourceProvider;
		this.threadPoolMsg = new F3Screen.NestedMessage(this::f3Log);
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override
	public ColumnRenderSource get(DhSectionPos pos)
	{
		// call the full data provider to make sure the full data is up to date
		// and any necessary world generation has been queued/completed
		this.fullDataSourceProvider.queuePositionForGenerationOrRetrievalIfNecessary(pos);
		
		return super.get(pos);
	}
	
	
	//====================//
	// Abstract overrides //
	//====================//
	
	@Override
	protected AbstractLegacyDataSourceRepo createRepo()
	{
		try
		{
			return new RenderDataRepo("jdbc:sqlite", this.saveDir.getPath() + "/" + AbstractSaveStructure.DATABASE_NAME);
		}
		catch (SQLException e)
		{
			// should only happen if there is an issue with the database (it's locked or can't be created if missing) 
			// or the database update failed
			throw new RuntimeException(e);
		}
	}
	
	@Override 
	protected ColumnRenderSource createDataSourceFromDto(LegacyDataSourceDTO dto) throws InterruptedException, IOException
	{ return ColumnRenderSourceLoader.INSTANCE.loadRenderSource(dto, dto.getInputStream(), this.level); }
	@Override 
	protected ColumnRenderSource createNewDataSourceFromExistingDtos(DhSectionPos pos) 
	{
		ColumnRenderSource renderDataSource;
		
		NewFullDataSource fullDataSource = this.fullDataSourceProvider.get(pos);
		renderDataSource = FullDataToRenderDataTransformer.transformFullDataToRenderSource(fullDataSource, this.level);
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
		ThreadPoolExecutor fileExecutor = ThreadPoolUtil.getFileHandlerExecutor();
		String fileQueueSize = (fileExecutor != null) ? fileExecutor.getQueue().size()+"" : "-";
		String fileCompletedTaskSize = (fileExecutor != null) ? fileExecutor.getCompletedTaskCount()+"" : "-";
		
		ThreadPoolExecutor updateExecutor = ThreadPoolUtil.getUpdatePropagatorExecutor();
		String updateQueueSize = (updateExecutor != null) ? updateExecutor.getQueue().size()+"" : "-";
		String updateCompletedTaskSize = (updateExecutor != null) ? updateExecutor.getCompletedTaskCount()+"" : "-";
		
		ArrayList<String> lines = new ArrayList<>();
		lines.add("File Handler [" + this.level.getLevelWrapper().getDimensionType().getDimensionName() + "]");
		lines.add("  File thread pool tasks: " + fileQueueSize + " (completed: " + fileCompletedTaskSize + ")");
		lines.add("  Update thread pool tasks: " + updateQueueSize + " (completed: " + updateCompletedTaskSize + ")");
		lines.add("  Unsaved render sources: " + this.unsavedDataSourceBySectionPos.size());
		
		return lines.toArray(new String[0]);
	}
	
	@Override
	public CompletableFuture<Void> updateDataSourceAsync(NewFullDataSource inputDataSource)
	{
		this.updateDataSourceAtPos(inputDataSource.getSectionPos(), inputDataSource);
		return CompletableFuture.completedFuture(null);
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
