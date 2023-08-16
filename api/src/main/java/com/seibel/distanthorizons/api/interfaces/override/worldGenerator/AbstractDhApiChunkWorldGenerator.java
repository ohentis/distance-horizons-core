package com.seibel.distanthorizons.api.interfaces.override.worldGenerator;

import com.seibel.distanthorizons.api.enums.EDhApiDetailLevel;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.interfaces.override.IDhApiOverrideable;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * @author James Seibel
 * @version 2023-6-22
 */
public abstract class AbstractDhApiChunkWorldGenerator implements Closeable, IDhApiOverrideable, IDhApiWorldGenerator
{
	//============//
	// parameters //
	//============//
	
	@Override
	public final byte getSmallestDataDetailLevel() { return EDhApiDetailLevel.BLOCK.detailLevel; }
	@Override
	public final byte getLargestDataDetailLevel() { return EDhApiDetailLevel.BLOCK.detailLevel; }
	@Override
	public final byte getMinGenerationGranularity() { return EDhApiDetailLevel.CHUNK.detailLevel; }
	@Override
	public final byte getMaxGenerationGranularity() { return (byte) (EDhApiDetailLevel.CHUNK.detailLevel + 2); }
	
	
	
	//=================//
	// world generator //
	//=================//
	
	@Override
	public final CompletableFuture<Void> generateChunks(
			int chunkPosMinX, int chunkPosMinZ,
			byte granularity, byte targetDataDetail, EDhApiDistantGeneratorMode generatorMode,
			ExecutorService worldGeneratorThreadPool, Consumer<Object[]> resultConsumer) throws ClassCastException
	{
		return CompletableFuture.runAsync(() ->
		{
			// TODO what does this mean?
			int genChunkWidth = BitShiftUtil.powerOfTwo(granularity - 4);
			
			for (int chunkX = chunkPosMinX; chunkX < chunkPosMinX + genChunkWidth; chunkX++)
			{
				for (int chunkZ = chunkPosMinZ; chunkZ < chunkPosMinZ + genChunkWidth; chunkZ++)
				{
					Object[] rawMcObjectArray = this.generateChunk(chunkX, chunkZ, generatorMode);
					resultConsumer.accept(rawMcObjectArray);
				}
			}
		}, worldGeneratorThreadPool);
	}
	
	/**
	 * This method is called to generate terrain over a given area
	 * from a thread defined by Distant Horizons. <br><br>
	 *
	 * See {@link IDhApiWorldGenerator#generateChunks(int, int, byte, byte, EDhApiDistantGeneratorMode, ExecutorService, Consumer) IDhApiWorldGenerator.generateChunks}
	 * for the list of Object's this method should return along with additional documentation.
	 *
	 * @see IDhApiWorldGenerator#generateChunks(int, int, byte, byte, EDhApiDistantGeneratorMode, ExecutorService, Consumer) IDhApiWorldGenerator#generateChunks
	 */
	public abstract Object[] generateChunk(int chunkPosX, int chunkPosZ, EDhApiDistantGeneratorMode generatorMode);
	
}
