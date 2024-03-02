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
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.NewFullDataSource;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.file.fullDatafile.IFullDataSourceProvider;
import com.seibel.distanthorizons.core.file.fullDatafile.NewFullDataFileHandler;
import com.seibel.distanthorizons.core.render.LodRenderSection;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadNode;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.util.Iterator;

/** The level used on a singleplayer world */
public class DhClientServerLevel extends AbstractDhLevel implements IDhClientLevel, IDhServerLevel
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	
	public final ServerLevelModule serverside;
	public final ClientLevelModule clientside;
	
	private final IServerLevelWrapper serverLevelWrapper;
	
	
	
	public DhClientServerLevel(AbstractSaveStructure saveStructure, IServerLevelWrapper serverLevelWrapper)
	{
		if (saveStructure.getFullDataFolder(serverLevelWrapper).mkdirs())
		{
			LOGGER.warn("unable to create data folder.");
		}
		this.serverLevelWrapper = serverLevelWrapper;
		this.serverside = new ServerLevelModule(this, saveStructure);
		this.clientside = new ClientLevelModule(this);
		LOGGER.info("Started " + DhClientServerLevel.class.getSimpleName() + " for " + serverLevelWrapper + " with saves at " + saveStructure);
	}
	
	
	
	//==============//
	// tick methods //
	//==============//
	
	@Override
	public void clientTick()
	{
		clientside.clientTick();
	}
	
	@Override
	public void render(DhApiRenderParam renderEventParam, IProfilerWrapper profiler)
	{ this.clientside.render(renderEventParam, profiler); }
	
	@Override
	public void renderDeferred(DhApiRenderParam renderEventParam, IProfilerWrapper profiler)
	{ this.clientside.renderDeferred(renderEventParam, profiler); }
	
	@Override
	public void serverTick() { this.chunkToLodBuilder.tick(); }
	
	@Override
	public void doWorldGen()
	{
		this.serverside.worldGeneratorEnabledConfig.pollNewValue(); // if not called the get() line below may not 
		boolean shouldDoWorldGen = this.serverside.worldGeneratorEnabledConfig.get() && this.clientside.isRendering();
		boolean isWorldGenRunning = this.serverside.worldGenModule.isWorldGenRunning();
		if (shouldDoWorldGen && !isWorldGenRunning)
		{
			// start world gen
			
			// create a new queue
			this.serverside.worldGenModule.startWorldGen(this.serverside.dataFileHandler, new ServerLevelModule.WorldGenState(this));
			
			// populate the queue based on the current rendering tree
			ClientLevelModule.ClientRenderState renderState = this.clientside.ClientRenderStateRef.get();
			Iterator<QuadNode<LodRenderSection>> iterator = renderState.quadtree.leafNodeIterator();
			while (iterator.hasNext())
			{
				QuadNode<LodRenderSection> node = iterator.next();
				this.serverside.dataFileHandler.getAsync(node.sectionPos);
			}
		}
		else if (!shouldDoWorldGen && isWorldGenRunning)
		{
			// stop world gen
			this.serverside.worldGenModule.stopWorldGen(this.serverside.dataFileHandler);
		}
		
		if (isWorldGenRunning)
		{
			ClientLevelModule.ClientRenderState renderState = this.clientside.ClientRenderStateRef.get();
			if (renderState != null && renderState.quadtree != null)
			{
				// remove any generator sections that are out of bounds
				this.serverside.dataFileHandler.removeGenRequestIf(pos -> !renderState.quadtree.isSectionPosInBounds(pos));
			}
			
			this.serverside.worldGenModule.worldGenTick(new DhBlockPos2D(MC_CLIENT.getPlayerBlockPos()));
		}
	}
	
	//========//
	// render //
	//========//
	
	public void startRenderer(IClientLevelWrapper clientLevel) { this.clientside.startRenderer(clientLevel); }
	
	public void stopRenderer() { this.clientside.stopRenderer(); }
	
	//================//
	// level handling //
	//================//
	
	@Override //FIXME this can fail if the clientLevel isn't available yet, maybe in that case we could return -1 and handle it upstream?
	public int computeBaseColor(DhBlockPos pos, IBiomeWrapper biome, IBlockStateWrapper block)
	{
		IClientLevelWrapper clientLevel = this.getClientLevelWrapper();
		if (clientLevel == null)
		{
			return 0;
		}
		else
		{
			return clientLevel.computeBaseColor(pos, biome, block);
		}
	}
	
	@Override
	public IClientLevelWrapper getClientLevelWrapper() { return this.serverLevelWrapper.tryGetClientLevelWrapper(); }
	
	@Override
	public void clearRenderCache()
	{
		clientside.clearRenderCache();
	}
	
	@Override
	public IServerLevelWrapper getServerLevelWrapper() { return serverLevelWrapper; }
	@Override
	public ILevelWrapper getLevelWrapper() { return getServerLevelWrapper(); }
	
	@Override
	public NewFullDataFileHandler getFullDataProvider() { return this.serverside.dataFileHandler; }
	
	@Override
	public AbstractSaveStructure getSaveStructure()
	{
		return serverside.saveStructure;
	}
	
	@Override
	public boolean hasSkyLight() { return this.serverLevelWrapper.hasSkyLight(); }
	
	@Override
	public void updateDataSources(NewFullDataSource data) { this.clientside.updateDataSources(data); }
	
	@Override
	public int getMinY() { return getLevelWrapper().getMinHeight(); }
	
	
	
	//===============//
	// data handling //
	//===============//
	
	@Override
	public void close()
	{
		this.clientside.close();
		super.close();
		this.serverside.close();
		LOGGER.info("Closed " + this.getClass().getSimpleName() + " for " + this.getServerLevelWrapper());
	}
	
	@Override
	public void onWorldGenTaskComplete(DhSectionPos pos)
	{
		DebugRenderer.makeParticle(
				new DebugRenderer.BoxParticle(
						new DebugRenderer.Box(pos, 128f, 156f, 0.09f, Color.red.darker()),
						0.2, 32f
				)
		);
		
		this.clientside.reloadPos(pos);
	}
	
}
