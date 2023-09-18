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

package com.seibel.distanthorizons.core.dataObjects.transformers;

import com.seibel.distanthorizons.api.enums.config.EBlocksToAvoid;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.SingleColumnFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IIncompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.ColumnArrayView;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Handles converting {@link ChunkSizedFullDataAccessor}, {@link IIncompleteFullDataSource},
 * and {@link IFullDataSource}'s to {@link ColumnRenderSource}.
 */
public class FullDataToRenderDataTransformer
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	private static final IWrapperFactory WRAPPER_FACTORY = SingletonInjector.INSTANCE.get(IWrapperFactory.class);
	private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	
	private static ExecutorService transformerThreadPool = null;
	private static ConfigChangeListener<Integer> configListener;
	
	
	
	//==============================//
	// public transformer interface //
	//==============================//
	
	public static CompletableFuture<ColumnRenderSource> transformFullDataToRenderSourceAsync(IFullDataSource fullDataSource, IDhClientLevel level) { return CompletableFuture.supplyAsync(() -> transformFullDataToRenderSource(fullDataSource, level), transformerThreadPool); }
	public static CompletableFuture<ColumnRenderSource> transformFullDataToRenderSourceAsync(CompletableFuture<IFullDataSource> fullDataSourceFuture, IDhClientLevel level) { return fullDataSourceFuture.thenApplyAsync((fullDataSource) -> transformFullDataToRenderSource(fullDataSource, level), transformerThreadPool); }
	private static ColumnRenderSource transformFullDataToRenderSource(IFullDataSource fullDataSource, IDhClientLevel level)
	{
		if (fullDataSource == null)
		{
			return null;
		}
		else if (MC.getWrappedClientWorld() == null)
		{
			// if the client is no longer loaded in the world, render sources cannot be created 
			return null;
		}
		
		
		try
		{
			if (fullDataSource instanceof CompleteFullDataSource)
			{
				return transformCompleteFullDataToColumnData(level, (CompleteFullDataSource) fullDataSource);
			}
			else if (fullDataSource instanceof IIncompleteFullDataSource)
			{
				return transformIncompleteFullDataToColumnData(level, (IIncompleteFullDataSource) fullDataSource);
			}
			
			LodUtil.assertNotReach("Unimplemented Full Data transformer for "+IFullDataSource.class.getSimpleName()+" of type ["+fullDataSource.getClass().getSimpleName()+"].");
			return null;
		}
		catch (InterruptedException e)
		{
			return null;
		}
	}
	
	
	
	//==============//
	// transformers //
	//==============//
	
	/**
	 * Creates a LodNode for a chunk in the given world.
	 *
	 * @throws IllegalArgumentException thrown if either the chunk or world is null.
	 * @throws InterruptedException Can be caused by interrupting the thread upstream.
	 * Generally thrown if the method is running after the client leaves the current world.
	 */
	private static ColumnRenderSource transformCompleteFullDataToColumnData(IDhClientLevel level, CompleteFullDataSource fullDataSource) throws InterruptedException
	{
		final DhSectionPos pos = fullDataSource.getSectionPos();
		final byte dataDetail = fullDataSource.getDataDetailLevel();
		final int vertSize = Config.Client.Advanced.Graphics.Quality.verticalQuality.get().calculateMaxVerticalData(fullDataSource.getDataDetailLevel());
		final ColumnRenderSource columnSource = new ColumnRenderSource(pos, vertSize, level.getMinY());
		if (fullDataSource.isEmpty())
		{
			return columnSource;
		}
		
		columnSource.markNotEmpty();
		
		if (dataDetail == columnSource.getDataDetail())
		{
			int baseX = pos.getMinCornerLodPos().getCornerBlockPos().x;
			int baseZ = pos.getMinCornerLodPos().getCornerBlockPos().z;
			
			for (int x = 0; x < pos.getWidthCountForLowerDetailedSection(dataDetail); x++)
			{
				for (int z = 0; z < pos.getWidthCountForLowerDetailedSection(dataDetail); z++)
				{
					throwIfThreadInterrupted();
					
					ColumnArrayView columnArrayView = columnSource.getVerticalDataPointView(x, z);
					SingleColumnFullDataAccessor fullArrayView = fullDataSource.get(x, z);
					convertColumnData(level, baseX + x, baseZ + z, columnArrayView, fullArrayView, 1);
					
					if (fullArrayView.doesColumnExist())
					{
						LodUtil.assertTrue(columnSource.doesDataPointExist(x, z));
					}
				}
			}
			
			columnSource.fillDebugFlag(0, 0, ColumnRenderSource.SECTION_SIZE, ColumnRenderSource.SECTION_SIZE, ColumnRenderSource.DebugSourceFlag.FULL);
			
		}
		else
		{
			throw new UnsupportedOperationException("To be implemented");
			//FIXME: Implement different size creation of renderData
		}
		return columnSource;
	}
	
	/**
	 * @throws InterruptedException Can be caused by interrupting the thread upstream.
	 * Generally thrown if the method is running after the client leaves the current world.
	 */
	private static ColumnRenderSource transformIncompleteFullDataToColumnData(IDhClientLevel level, IIncompleteFullDataSource data) throws InterruptedException
	{
		final DhSectionPos pos = data.getSectionPos();
		final byte dataDetail = data.getDataDetailLevel();
		final int vertSize = Config.Client.Advanced.Graphics.Quality.verticalQuality.get().calculateMaxVerticalData(data.getDataDetailLevel());
		final ColumnRenderSource columnSource = new ColumnRenderSource(pos, vertSize, level.getMinY());
		if (data.isEmpty())
		{
			return columnSource;
		}
		
		columnSource.markNotEmpty();
		
		if (dataDetail == columnSource.getDataDetail())
		{
			int baseX = pos.getMinCornerLodPos().getCornerBlockPos().x;
			int baseZ = pos.getMinCornerLodPos().getCornerBlockPos().z;
			for (int x = 0; x < pos.getWidthCountForLowerDetailedSection(dataDetail); x++)
			{
				for (int z = 0; z < pos.getWidthCountForLowerDetailedSection(dataDetail); z++)
				{
					throwIfThreadInterrupted();
					
					SingleColumnFullDataAccessor fullArrayView = data.tryGet(x, z);
					if (fullArrayView == null)
					{
						continue;
					}
					
					ColumnArrayView columnArrayView = columnSource.getVerticalDataPointView(x, z);
					convertColumnData(level, baseX + x, baseZ + z, columnArrayView, fullArrayView, 1);
					
					columnSource.fillDebugFlag(x, z, 1, 1, ColumnRenderSource.DebugSourceFlag.SPARSE);
					if (fullArrayView.doesColumnExist())
						LodUtil.assertTrue(columnSource.doesDataPointExist(x, z));
				}
			}
		}
		else
		{
			throw new UnsupportedOperationException("To be implemented");
			//FIXME: Implement different size creation of renderData
		}
		return columnSource;
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	/**
	 * Called in loops that may run for an extended period of time. <br>
	 * This is necessary to allow canceling these transformers since running
	 * them after the client has left a given world will throw exceptions.
	 */
	private static void throwIfThreadInterrupted() throws InterruptedException
	{
		if (Thread.interrupted())
		{
			throw new InterruptedException(FullDataToRenderDataTransformer.class.getSimpleName() + " task interrupted.");
		}
	}
	
	
	// TODO what does this mean?
	private static void iterateAndConvert(IDhClientLevel level, int blockX, int blockZ, int genMode, ColumnArrayView column, SingleColumnFullDataAccessor data)
	{
		boolean avoidSolidBlocks = (Config.Client.Advanced.Graphics.Quality.blocksToIgnore.get() == EBlocksToAvoid.NON_COLLIDING);
		boolean colorBelowWithAvoidedBlocks = Config.Client.Advanced.Graphics.Quality.tintWithAvoidedBlocks.get();
		
		FullDataPointIdMap fullDataMapping = data.getMapping();
		HashSet<IBlockStateWrapper> blockStatesToIgnore = WRAPPER_FACTORY.getRendererIgnoredBlocks(level.getLevelWrapper());
		
		boolean isVoid = true;
		int colorToApplyToNextBlock = -1;
		int columnOffset = 0;
		
		// goes from the top down
		for (int i = 0; i < data.getSingleLength(); i++)
		{
			long fullData = data.getSingle(i);
			int bottomY = FullDataPointUtil.getBottomY(fullData);
			int blockHeight = FullDataPointUtil.getHeight(fullData);
			int id = FullDataPointUtil.getId(fullData);
			int light = FullDataPointUtil.getLight(fullData);
			IBiomeWrapper biome = fullDataMapping.getBiomeWrapper(id);
			IBlockStateWrapper block = fullDataMapping.getBlockStateWrapper(id);
			
			if (blockStatesToIgnore.contains(block))
			{
				// Don't render: air, barriers, light blocks, etc.
				continue;
			}
			
			
			// solid block check
			if (avoidSolidBlocks && !block.isSolid() && !block.isLiquid())
			{
				if (colorBelowWithAvoidedBlocks)
				{
					colorToApplyToNextBlock = level.computeBaseColor(new DhBlockPos(blockX, bottomY + level.getMinY(), blockZ), biome, block);
				}
				
				// don't add this block
				continue;
			}
			
			
			int color;
			if (colorToApplyToNextBlock == -1)
			{
				// use this block's color
				color = level.computeBaseColor(new DhBlockPos(blockX, bottomY + level.getMinY(), blockZ), biome, block);
			}
			else
			{
				// use the previous block's color
				color = colorToApplyToNextBlock;
				colorToApplyToNextBlock = -1;
			}
			
			
			// add the block
			isVoid = false;
			long columnData = RenderDataPointUtil.createDataPoint(bottomY + blockHeight, bottomY, color, light, genMode);
			column.set(columnOffset, columnData);
			columnOffset++;
		}
		
		
		if (isVoid)
		{
			column.set(0, RenderDataPointUtil.createVoidDataPoint((byte) genMode));
		}
	}
	
	// TODO what does this mean?
	public static void convertColumnData(IDhClientLevel level, int blockX, int blockZ, ColumnArrayView columnArrayView, SingleColumnFullDataAccessor fullArrayView, int genMode)
	{
		if (!fullArrayView.doesColumnExist())
		{
			return;
		}
		
		int dataTotalLength = fullArrayView.getSingleLength();
		if (dataTotalLength == 0)
		{
			return;
		}
		
		if (dataTotalLength > columnArrayView.verticalSize())
		{
			ColumnArrayView totalColumnData = new ColumnArrayView(new long[dataTotalLength], dataTotalLength, 0, dataTotalLength);
			iterateAndConvert(level, blockX, blockZ, genMode, totalColumnData, fullArrayView);
			columnArrayView.changeVerticalSizeFrom(totalColumnData);
		}
		else
		{
			iterateAndConvert(level, blockX, blockZ, genMode, columnArrayView, fullArrayView); //Directly use the arrayView since it fits.
		}
	}
	
	
	
	//==========================//
	// executor handler methods //
	//==========================//
	
	/**
	 * Creates a new executor. <br>
	 * Does nothing if an executor already exists.
	 */
	public static void setupExecutorService()
	{
		// static setup
		if (configListener == null)
		{
			configListener = new ConfigChangeListener<>(Config.Client.Advanced.MultiThreading.numberOfDataTransformerThreads, (threadCount) -> { setThreadPoolSize(threadCount); });
		}
		
		
		// TODO this didn't seem to be re-sizing when changed via the config
		if (transformerThreadPool == null || transformerThreadPool.isTerminated())
		{
			LOGGER.info("Starting " + FullDataToRenderDataTransformer.class.getSimpleName());
			setThreadPoolSize(Config.Client.Advanced.MultiThreading.numberOfDataTransformerThreads.get());
		}
	}
	public static void setThreadPoolSize(int threadPoolSize)
	{
		if (transformerThreadPool != null)
		{
			// close the previous thread pool if one exists
			transformerThreadPool.shutdown();
		}
		
		transformerThreadPool = ThreadUtil.makeRateLimitedThreadPool(threadPoolSize, "Full/Render Data Transformer", Config.Client.Advanced.MultiThreading.runTimeRatioForDataTransformerThreads);
	}
	
	/**
	 * Stops any executing tasks and destroys the executor. <br>
	 * Does nothing if the executor isn't running.
	 */
	public static void shutdownExecutorService()
	{
		if (transformerThreadPool != null)
		{
			LOGGER.info("Stopping " + FullDataToRenderDataTransformer.class.getSimpleName());
			transformerThreadPool.shutdownNow();
		}
	}
	
}
