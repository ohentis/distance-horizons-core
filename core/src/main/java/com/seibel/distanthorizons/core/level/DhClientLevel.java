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

package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.file.fullDatafile.RemoteFullDataSourceProvider;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/** The level used when connected to a server */
public class DhClientLevel extends AbstractDhLevel implements IDhClientLevel
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	public final ClientLevelModule clientside;
	public final IClientLevelWrapper levelWrapper;
	public final AbstractSaveStructure saveStructure;
	public final RemoteFullDataSourceProvider dataFileHandler;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public DhClientLevel(AbstractSaveStructure saveStructure, IClientLevelWrapper clientLevelWrapper) { this(saveStructure, clientLevelWrapper, null, true); }
	public DhClientLevel(AbstractSaveStructure saveStructure, IClientLevelWrapper clientLevelWrapper, @Nullable File fullDataSaveDirOverride, boolean enableRendering)
	{
		this.levelWrapper = clientLevelWrapper;
		this.saveStructure = saveStructure;
		this.dataFileHandler = new RemoteFullDataSourceProvider(this, saveStructure, fullDataSaveDirOverride);
		this.clientside = new ClientLevelModule(this);
		
		this.createAndSetChunkHashRepo(this.dataFileHandler.repo.databaseLocation);
		
		if (enableRendering)
		{
			this.clientside.startRenderer(clientLevelWrapper);
			LOGGER.info("Started DHLevel for " + this.levelWrapper + " with saves at " + this.saveStructure);
		}
	}
	
	
	
	//==============//
	// tick methods //
	//==============//
	
	@Override
	public void clientTick()
	{
		try
		{
			this.chunkToLodBuilder.tick();
			this.clientside.clientTick();
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected clientTick Exception: "+e.getMessage(), e);
		}
	}
	
	@Override
	public void render(DhApiRenderParam renderEventParam, IProfilerWrapper profiler)
	{ this.clientside.render(renderEventParam, profiler); }
	
	@Override
	public void renderDeferred(DhApiRenderParam renderEventParam, IProfilerWrapper profiler)
	{ this.clientside.renderDeferred(renderEventParam, profiler); }
	
	
	
	//================//
	// level handling //
	//================//
	
	@Override
	public int computeBaseColor(DhBlockPos pos, IBiomeWrapper biome, IBlockStateWrapper block) { return this.levelWrapper.computeBaseColor(pos, biome, block); }
	
	@Override
	public IClientLevelWrapper getClientLevelWrapper() { return this.levelWrapper; }
	
	@Override
	public void clearRenderCache() { this.clientside.clearRenderCache(); }
	
	@Override
	public ILevelWrapper getLevelWrapper() { return this.levelWrapper; }
	
	@Override
	public CompletableFuture<Void> updateDataSourcesAsync(FullDataSourceV2 data) { return this.clientside.updateDataSourcesAsync(data); }
	
	@Override
	public int getMinY() { return this.levelWrapper.getMinHeight(); }
	
	@Override
	public void close()
	{
		this.clientside.close();
		super.close();
		this.dataFileHandler.close();
		LOGGER.info("Closed " + DhClientLevel.class.getSimpleName() + " for " + levelWrapper);
	}
	
	@Override
	public FullDataSourceProviderV2 getFullDataProvider() { return this.dataFileHandler; }
	
	@Override
	public AbstractSaveStructure getSaveStructure() { return this.saveStructure; }
	
	@Override
	public boolean hasSkyLight() { return this.levelWrapper.hasSkyLight(); }
	
	
}
