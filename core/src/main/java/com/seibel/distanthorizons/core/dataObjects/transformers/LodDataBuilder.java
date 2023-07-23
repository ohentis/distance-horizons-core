package com.seibel.distanthorizons.core.dataObjects.transformers;

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import it.unimi.dsi.fastutil.longs.LongArrayList;

public class LodDataBuilder
{
	
    private static final IBlockStateWrapper AIR = SingletonInjector.INSTANCE.get(IWrapperFactory.class).getAirBlockStateWrapper();
	
	
    public static ChunkSizedFullDataAccessor createChunkData(IChunkWrapper chunkWrapper)
    {
        if (!canGenerateLodFromChunk(chunkWrapper))
        {
			return null;
        }
		
	    
        ChunkSizedFullDataAccessor chunkData = new ChunkSizedFullDataAccessor(chunkWrapper.getChunkPos());

        for (int x=0; x<LodUtil.CHUNK_WIDTH; x++)
		{
            for (int z=0; z<LodUtil.CHUNK_WIDTH; z++)
			{
                LongArrayList longs = new LongArrayList(chunkWrapper.getHeight()/4);
                int lastY = chunkWrapper.getMaxBuildHeight();
                IBiomeWrapper biome = chunkWrapper.getBiome(x, lastY, z);
                IBlockStateWrapper blockState = AIR;
                int mappedId = chunkData.getMapping().addIfNotPresentAndGetId(biome, blockState);
                // FIXME: The +1 offset to reproduce the old behavior. Remove this when we get per-face lighting
                byte light = (byte) ((chunkWrapper.getBlockLight(x,lastY+1,z) << 4) + chunkWrapper.getSkyLight(x,lastY+1,z));

                int y=chunkWrapper.getLightBlockingHeightMapValue(x, z);

                for (; y>=chunkWrapper.getMinBuildHeight(); y--)
				{
                    IBiomeWrapper newBiome = chunkWrapper.getBiome(x, y, z);
                    IBlockStateWrapper newBlockState = chunkWrapper.getBlockState(x, y, z);
                    byte newLight = (byte) ((chunkWrapper.getBlockLight(x,y+1,z) << 4) + chunkWrapper.getSkyLight(x,y+1,z));

                    if (!newBiome.equals(biome) || !newBlockState.equals(blockState))
					{
                        longs.add(FullDataPointUtil.encode(mappedId, lastY-y, y+1 - chunkWrapper.getMinBuildHeight(), light));
                        biome = newBiome;
                        blockState = newBlockState;
                        mappedId = chunkData.getMapping().addIfNotPresentAndGetId(biome, blockState);
                        light = newLight;
                        lastY = y;
                    }
//                    else if (newLight != light) {
//                        longs.add(FullFormat.encode(mappedId, lastY-y, y+1 - chunk.getMinBuildHeight(), light));
//                        light = newLight;
//                        lastY = y;
//                    }
                }
                longs.add(FullDataPointUtil.encode(mappedId, lastY-y, y+1 - chunkWrapper.getMinBuildHeight(), light));

                chunkData.setSingleColumn(longs.toArray(new long[0]), x, z);
            }
        }
        if (!canGenerateLodFromChunk(chunkWrapper)) return null;
        LodUtil.assertTrue(chunkData.emptyCount() == 0);
        return chunkData;
    }

    public static boolean canGenerateLodFromChunk(IChunkWrapper chunk)
    {
        //return true;
        return chunk != null && chunk.isLightCorrect(); // TODO client only chunks return chunks with bad lighting, preventing chunk building (or transparent only chunks)
    }
}
