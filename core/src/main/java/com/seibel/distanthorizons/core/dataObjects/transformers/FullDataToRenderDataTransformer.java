package com.seibel.distanthorizons.core.dataObjects.transformers;

import com.seibel.distanthorizons.api.enums.config.EBlocksToAvoid;
import com.seibel.distanthorizons.core.config.Config;
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
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;

/**
 * Handles converting {@link ChunkSizedFullDataAccessor}, {@link IIncompleteFullDataSource},
 * and {@link IFullDataSource}'s to {@link ColumnRenderSource}.
 */
public class FullDataToRenderDataTransformer
{
    private static final IBlockStateWrapper AIR = SingletonInjector.INSTANCE.get(IWrapperFactory.class).getAirBlockStateWrapper();
	
	
	
	/**
	 * Called in loops that may run for an extended period of time. <br>
	 * This is necessary to allow canceling these transformers since running
	 * them after the client has left a given world will throw exceptions here.
	 */
	private static void throwIfThreadInterrupted() throws InterruptedException
	{
		if (Thread.interrupted())
		{
			throw new InterruptedException(FullDataToRenderDataTransformer.class.getSimpleName()+" task interrupted.");
		}
	}
	
	
	//==============//
	// transformers //
	//==============//
	
    /**
     * Creates a LodNode for a chunk in the given world.
     * @throws IllegalArgumentException thrown if either the chunk or world is null.
	 * @throws InterruptedException Can be caused by interrupting the thread upstream.
	 * 								Generally thrown if the method is running after the client leaves the current world.
     */
    public static ColumnRenderSource transformFullDataToColumnData(IDhClientLevel level, CompleteFullDataSource fullDataSource) throws InterruptedException 
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
            int baseX = pos.getCorner().getCornerBlockPos().x;
            int baseZ = pos.getCorner().getCornerBlockPos().z;
			
            for (int x = 0; x < pos.getWidth(dataDetail).numberOfLodSectionsWide; x++)
			{
                for (int z = 0; z < pos.getWidth(dataDetail).numberOfLodSectionsWide; z++)
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
	 * 								Generally thrown if the method is running after the client leaves the current world.
	 */
    public static ColumnRenderSource transformIncompleteDataToColumnData(IDhClientLevel level, IIncompleteFullDataSource data) throws InterruptedException
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
			int baseX = pos.getCorner().getCornerBlockPos().x;
			int baseZ = pos.getCorner().getCornerBlockPos().z;
			for (int x = 0; x < pos.getWidth(dataDetail).numberOfLodSectionsWide; x++)
			{
				for (int z = 0; z < pos.getWidth(dataDetail).numberOfLodSectionsWide; z++)
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

	/**
	 * @throws InterruptedException Can be caused by interrupting the thread upstream.
	 * 								Generally thrown if the method is running after the client leaves the current world.
	 */
	public static boolean writeFullDataChunkToColumnData(ColumnRenderSource renderSource, IDhClientLevel level, ChunkSizedFullDataAccessor chunkDataView) throws InterruptedException, IllegalArgumentException
	{
		final DhSectionPos renderSourcePos = renderSource.getSectionPos();
		
		final int sourceBlockX = renderSourcePos.getCorner().getCornerBlockPos().x;
		final int sourceBlockZ = renderSourcePos.getCorner().getCornerBlockPos().z;
		
		// offset between the incoming chunk data and this render source
		final int blockOffsetX = (chunkDataView.pos.x * LodUtil.CHUNK_WIDTH) - sourceBlockX;
		final int blockOffsetZ = (chunkDataView.pos.z * LodUtil.CHUNK_WIDTH) - sourceBlockZ;
		
		final int sourceDataPointBlockWidth = BitShiftUtil.powerOfTwo(renderSource.getDataDetail());
		
		boolean changed = false;

		if (chunkDataView.detailLevel == renderSource.getDataDetail())
		{
			renderSource.markNotEmpty();
			// confirm the render source contains this chunk
			if (blockOffsetX < 0
				|| blockOffsetX + LodUtil.CHUNK_WIDTH > renderSource.getWidthInDataPoints()
				|| blockOffsetZ < 0
				|| blockOffsetZ + LodUtil.CHUNK_WIDTH > renderSource.getWidthInDataPoints())
			{
				throw new IllegalArgumentException("Data offset is out of bounds");
			}

			throwIfThreadInterrupted();
			
			for (int x = 0; x < LodUtil.CHUNK_WIDTH; x++)
			{
				for (int z = 0; z < LodUtil.CHUNK_WIDTH; z++)
				{
					ColumnArrayView columnArrayView = renderSource.getVerticalDataPointView(blockOffsetX + x, blockOffsetZ + z);
					int hash = columnArrayView.getDataHash();
					SingleColumnFullDataAccessor fullArrayView = chunkDataView.get(x, z);
					convertColumnData(level, 
							sourceBlockX + sourceDataPointBlockWidth * (blockOffsetX + x),
							sourceBlockZ + sourceDataPointBlockWidth * (blockOffsetZ + z),
							columnArrayView, fullArrayView, 2);
					changed |= hash != columnArrayView.getDataHash();
				}
			}
			renderSource.fillDebugFlag(blockOffsetX, blockOffsetZ, LodUtil.CHUNK_WIDTH, LodUtil.CHUNK_WIDTH, ColumnRenderSource.DebugSourceFlag.DIRECT);
		}
		else if (chunkDataView.detailLevel < renderSource.getDataDetail() && renderSource.getDataDetail() <= chunkDataView.getLodPos().detailLevel) {
			renderSource.markNotEmpty();
			// multiple chunk data points converting to 1 column data point
			DhLodPos dataCornerPos = chunkDataView.getLodPos().getCornerLodPos(chunkDataView.detailLevel);
			DhLodPos sourceCornerPos = renderSourcePos.getCorner(renderSource.getDataDetail());
			DhLodPos sourceStartingChangePos = dataCornerPos.convertToDetailLevel(renderSource.getDataDetail());
			int relStartX = Math.floorMod(sourceStartingChangePos.x, renderSource.getWidthInDataPoints());
			int relStartZ = Math.floorMod(sourceStartingChangePos.z, renderSource.getWidthInDataPoints());
			int dataToSourceScale = sourceCornerPos.getWidthAtDetail(chunkDataView.detailLevel);
			int columnsInChunk = chunkDataView.getLodPos().getWidthAtDetail(renderSource.getDataDetail());

			for (int ox = 0; ox < columnsInChunk; ox++) {
				for (int oz = 0; oz < columnsInChunk; oz++) {
					int relSourceX = relStartX + ox;
					int relSourceZ = relStartZ + oz;
					ColumnArrayView columnArrayView = renderSource.getVerticalDataPointView(relSourceX, relSourceZ);
					int hash = columnArrayView.getDataHash();
					SingleColumnFullDataAccessor fullArrayView = chunkDataView.get(ox * dataToSourceScale, oz * dataToSourceScale);
					convertColumnData(level,
							sourceBlockX + sourceDataPointBlockWidth * relSourceX * dataToSourceScale,
							sourceBlockZ + sourceDataPointBlockWidth * relSourceZ * dataToSourceScale,
							columnArrayView, fullArrayView, 2);
					changed |= hash != columnArrayView.getDataHash();
				}
			}
			renderSource.fillDebugFlag(relStartX, relStartZ, columnsInChunk, columnsInChunk, ColumnRenderSource.DebugSourceFlag.DIRECT);
		}
		else if (chunkDataView.getLodPos().detailLevel < renderSource.getDataDetail()) {
			// The entire chunk is being converted to a single column data point, possibly.
			DhLodPos dataCornerPos = chunkDataView.getLodPos().getCornerLodPos(chunkDataView.detailLevel);
			DhLodPos sourceCornerPos = renderSourcePos.getCorner(renderSource.getDataDetail());
			DhLodPos sourceStartingChangePos = dataCornerPos.convertToDetailLevel(renderSource.getDataDetail());
			int chunksPerColumn = sourceStartingChangePos.getWidthAtDetail(chunkDataView.getLodPos().detailLevel);
			if (chunkDataView.getLodPos().x % chunksPerColumn != 0 || chunkDataView.getLodPos().z % chunksPerColumn != 0) {
				return false; // not a multiple of the column size, so no change
			}
			int relStartX = Math.floorMod(sourceStartingChangePos.x, renderSource.getWidthInDataPoints());
			int relStartZ = Math.floorMod(sourceStartingChangePos.z, renderSource.getWidthInDataPoints());
			ColumnArrayView columnArrayView = renderSource.getVerticalDataPointView(relStartX, relStartZ);
			int hash = columnArrayView.getDataHash();
			SingleColumnFullDataAccessor fullArrayView = chunkDataView.get(0, 0);
			convertColumnData(level, dataCornerPos.x * sourceDataPointBlockWidth,
					dataCornerPos.z * sourceDataPointBlockWidth,
					columnArrayView, fullArrayView, 2);
			changed = hash != columnArrayView.getDataHash();
			renderSource.fillDebugFlag(relStartX, relStartZ, 1, 1, ColumnRenderSource.DebugSourceFlag.DIRECT);
		}
		return changed;
	}

    private static void convertColumnData(IDhClientLevel level, int blockX, int blockZ, ColumnArrayView columnArrayView, SingleColumnFullDataAccessor fullArrayView, int genMode)
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
	
	private static void iterateAndConvert(IDhClientLevel level, int blockX, int blockZ, int genMode, ColumnArrayView column, SingleColumnFullDataAccessor data)
	{
		boolean avoidSolidBlocks = (Config.Client.Advanced.Graphics.Quality.blocksToIgnore.get() == EBlocksToAvoid.NON_COLLIDING);
		boolean colorBelowWithAvoidedBlocks = Config.Client.Advanced.Graphics.Quality.tintWithAvoidedBlocks.get();
		
		FullDataPointIdMap fullDataMapping = data.getMapping();
		
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
			if (block.equals(AIR))
			{
				// we don't render air
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
	
	
	
//    /** creates a vertical DataPoint */
//    private void writeVerticalData(long[] data, int dataOffset, int maxVerticalData,
//                                   IChunkWrapper chunk, LodBuilderConfig config, int chunkSubPosX, int chunkSubPosZ)
//    {
//
//        int totalVerticalData = (chunk.getHeight());
//        long[] dataToMerge = new long[totalVerticalData];
//
//        boolean hasCeiling = MC.getWrappedClientWorld().getDimensionType().hasCeiling();
//        boolean hasSkyLight = MC.getWrappedClientWorld().getDimensionType().hasSkyLight();
//        byte generation = config.distanceGenerationMode.complexity;
//        int count = 0;
//        // FIXME: This yAbs is just messy!
//        int x = chunk.getMinX() + chunkSubPosX;
//        int z = chunk.getMinZ() + chunkSubPosZ;
//        int y = chunk.getMaxY(x, z);
//
//        boolean topBlock = true;
//        if (y < chunk.getMinBuildHeight())
//            dataToMerge[0] = DataPointUtil.createVoidDataPoint(generation);
//        int maxConnectedLods = Config.Client.Graphics.Quality.verticalQuality.get().maxVerticalData[0];
//        while (y >= chunk.getMinBuildHeight()) {
//            int height = determineHeightPointFrom(chunk, config, x, y, z);
//            // If the lod is at the default height, it must be void data
//            if (height < chunk.getMinBuildHeight()) {
//                if (topBlock) dataToMerge[0] = DataPointUtil.createVoidDataPoint(generation);
//                break;
//            }
//            y = height - 1;
//            // We search light on above air block
//            int depth = determineBottomPointFrom(chunk, config, x, y, z,
//                    count < maxConnectedLods && (!hasCeiling || !topBlock));
//            if (hasCeiling && topBlock)
//                y = depth;
//            int light = getLightValue(chunk, x, y, z, hasCeiling, hasSkyLight, topBlock);
//            int color = generateLodColor(chunk, config, x, y, z);
//            int lightBlock = light & 0b1111;
//            int lightSky = (light >> 4) & 0b1111;
//            dataToMerge[count] = DataPointUtil.createDataPoint(height-chunk.getMinBuildHeight(), depth-chunk.getMinBuildHeight(),
//                    color, lightSky, lightBlock, generation);
//            topBlock = false;
//            y = depth - 1;
//            count++;
//        }
//        long[] result = DataPointUtil.mergeMultiData(dataToMerge, totalVerticalData, maxVerticalData);
//        if (result.length != maxVerticalData) throw new ArrayIndexOutOfBoundsException();
//        System.arraycopy(result, 0, data, dataOffset, maxVerticalData);
//    }
//
//    public static final EDhDirection[] DIRECTIONS = new EDhDirection[] {
//            EDhDirection.UP,
//            EDhDirection.DOWN,
//            EDhDirection.WEST,
//            EDhDirection.EAST,
//            EDhDirection.NORTH,
//            EDhDirection.SOUTH };
//
//    private boolean hasCliffFace(IChunkWrapper chunk, int x, int y, int z) {
//        for (EDhDirection dir : DIRECTIONS) {
//            IBlockDetailWrapper block = chunk.getBlockDetailAtFace(x, y, z, dir);
//            if (block == null || !block.hasFaceCullingFor(EDhDirection.OPPOSITE_DIRECTIONS[dir.ordinal()]))
//                return true;
//        }
//        return false;
//    }
//
//    /**
//     * Find the lowest valid point from the bottom.
//     * Used when creating a vertical LOD.
//     */
//    private int determineBottomPointFrom(IChunkWrapper chunk, LodBuilderConfig builderConfig, int xAbs, int yAbs, int zAbs, boolean strictEdge)
//    {
//        int depth = chunk.getMinBuildHeight();
//        IBlockDetailWrapper currentBlockDetail = null;
//        if (strictEdge)
//        {
//            IBlockDetailWrapper blockAbove = chunk.getBlockDetail(xAbs, yAbs + 1, zAbs);
//            if (blockAbove != null && Config.Client.WorldGenerator.tintWithAvoidedBlocks.get() && !blockAbove.shouldRender(Config.Client.WorldGenerator.blocksToAvoid.get()))
//            { // The above block is skipped. Lets use its skipped color for current block
//                currentBlockDetail = blockAbove;
//            }
//            if (currentBlockDetail == null) currentBlockDetail = chunk.getBlockDetail(xAbs, yAbs, zAbs);
//        }
//
//        for (int y = yAbs - 1; y >= chunk.getMinBuildHeight(); y--)
//        {
//            IBlockDetailWrapper nextBlock = chunk.getBlockDetail(xAbs, y, zAbs);
//            if (isLayerValidLodPoint(nextBlock)) {
//                if (!strictEdge) continue;
//                if (currentBlockDetail.equals(nextBlock)) continue;
//                if (!hasCliffFace(chunk, xAbs, y, zAbs)) continue;
//            }
//            depth = (y + 1);
//            break;
//        }
//        return depth;
//    }
//
//    /** Find the highest valid point from the Top */
//    private int determineHeightPointFrom(IChunkWrapper chunk, LodBuilderConfig config, int xAbs, int yAbs, int zAbs)
//    {
//        //TODO find a way to skip bottom of the world
//        int height = chunk.getMinBuildHeight()-1;
//        for (int y = yAbs; y >= chunk.getMinBuildHeight(); y--)
//        {
//            if (isLayerValidLodPoint(chunk, xAbs, y, zAbs))
//            {
//                height = (y + 1);
//                break;
//            }
//        }
//        return height;
//    }
//
//
//
//    // =====================//
//    // constructor helpers //
//    // =====================//
//
//    /**
//     * Generate the color for the given chunk using biome water color, foliage
//     * color, and grass color.
//     */
//    private int generateLodColor(IChunkWrapper chunk, LodBuilderConfig builderConfig, int x, int y, int z)
//    {
//        int colorInt;
//        if (builderConfig.useBiomeColors)
//        {
//            // I have no idea why I need to bit shift to the right, but
//            // if I don't the biomes don't show up correctly.
//            colorInt = chunk.getBiome(x, y, z).getColorForBiome(x, z);
//        }
//        else
//        {
//            // if we are skipping non-full and non-solid blocks that means we ignore
//            // snow, flowers, etc. Get the above block so we can still get the color
//            // of the snow, flower, etc. that may be above this block
//            colorInt = 0;
//            if (chunk.blockPosInsideChunk(x, y+1, z)) {
//                IBlockDetailWrapper blockAbove = chunk.getBlockDetail(x, y+1, z);
//                if (blockAbove != null && Config.Client.WorldGenerator.tintWithAvoidedBlocks.get() && !blockAbove.shouldRender(Config.Client.WorldGenerator.blocksToAvoid.get()))
//                {  // The above block is skipped. Lets use its skipped color for current block
//                    colorInt = blockAbove.getAndResolveFaceColor(null, chunk, new DHBlockPos(x, y+1, z));
//                }
//            }
//
//            // override this block's color if there was a block above this
//            // and we were avoiding non-full/non-solid blocks
//            if (colorInt == 0) {
//                IBlockDetailWrapper detail = chunk.getBlockDetail(x, y, z);
//                colorInt = detail.getAndResolveFaceColor(null, chunk, new DHBlockPos(x, y, z));
//            }
//        }
//
//        return colorInt;
//    }
//
//    /** Gets the light value for the given block position */
//    private int getLightValue(IChunkWrapper chunk, int x, int y, int z, boolean hasCeiling, boolean hasSkyLight, boolean topBlock)
//    {
//        int skyLight;
//        int blockLight;
//
//        int blockBrightness = chunk.getEmittedBrightness(x, y, z);
//        // get the air block above or below this block
//        if (hasCeiling && topBlock)
//            y--;
//        else
//            y++;
//
//        blockLight = chunk.getBlockLight(x, y, z);
//        skyLight = hasSkyLight ? chunk.getSkyLight(x, y, z) : 0;
//
//        if (blockLight == -1 || skyLight == -1)
//        {
//
//            ILevelWrapper world = MC.getWrappedServerWorld();
//
//            if (world != null)
//            {
//                // server world sky light (always accurate)
//                blockLight = world.getBlockLight(x, y, z);
//
//                if (topBlock && !hasCeiling && hasSkyLight)
//                    skyLight = DEFAULT_MAX_LIGHT;
//                else
//                    skyLight = hasSkyLight ? world.getSkyLight(x, y, z) : 0;
//
//                if (!topBlock && skyLight == 15)
//                {
//                    // we are on predicted terrain, and we don't know what the light here is,
//                    // lets just take a guess
//                    skyLight = 12;
//                }
//            }
//            else
//            {
//                world = MC.getWrappedClientWorld();
//                if (world == null)
//                {
//                    blockLight = 0;
//                    skyLight = 12;
//                }
//                else
//                {
//                    // client world sky light (almost never accurate)
//                    blockLight = world.getBlockLight(x, y, z);
//                    // estimate what the lighting should be
//                    if (hasSkyLight || !hasCeiling)
//                    {
//                        if (topBlock)
//                            skyLight = DEFAULT_MAX_LIGHT;
//                        else
//                        {
//                            if (hasSkyLight)
//                                skyLight = world.getSkyLight(x, y, z);
//                            //else
//                            //	skyLight = 0;
//                            if (!chunk.isLightCorrect() && (skyLight == 0 || skyLight == 15))
//                            {
//                                // we don't know what the light here is,
//                                // lets just take a guess
//                                skyLight = 12;
//                            }
//                        }
//                    }
//                }
//            }
//        }
//
//        blockLight = LodUtil.clamp(0, Math.max(blockLight, blockBrightness), DEFAULT_MAX_LIGHT);
//        return blockLight + (skyLight << 4);
//    }
//
//    /** Is the block at the given blockPos a valid LOD point? */
//    private boolean isLayerValidLodPoint(IBlockDetailWrapper blockDetail)
//    {
//        EBlocksToAvoid avoid = Config.Client.WorldGenerator.blocksToAvoid.get();
//        return blockDetail != null && blockDetail.shouldRender(avoid);
//    }
//
//    /** Is the block at the given blockPos a valid LOD point? */
//    private boolean isLayerValidLodPoint(IChunkWrapper chunk, int x, int y, int z) {
//        EBlocksToAvoid avoid = Config.Client.WorldGenerator.blocksToAvoid.get();
//        IBlockDetailWrapper block = chunk.getBlockDetail(x, y, z);
//        return block != null && block.shouldRender(avoid);
//    }
}
