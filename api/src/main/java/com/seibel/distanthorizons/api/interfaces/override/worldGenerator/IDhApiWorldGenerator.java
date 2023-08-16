package com.seibel.distanthorizons.api.interfaces.override.worldGenerator;

import com.seibel.distanthorizons.api.interfaces.override.IDhApiOverrideable;
import com.seibel.distanthorizons.api.enums.EDhApiDetailLevel;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * @author James Seibel
 * @version 2023-6-22
 */
public interface IDhApiWorldGenerator extends Closeable, IDhApiOverrideable
{
	//============//
	// parameters //
	//============//
	
	/*
	 * Returns which thread chunk generation requests will be run on. <br>
	 * TODO: only {@link EDhApiWorldGenThreadMode#MULTI_THREADED} is implemented
	 */
	//EDhApiWorldGenThreadMode getThreadingMode();
	
	/**
	 * Defines the smallest datapoint size that can be generated at a time. <br>
	 * Minimum detail level is 0 (1 block) <br>
	 * Default detail level is 0 <br>
	 * For more information on what detail levels represent see: {@link EDhApiDetailLevel}. <br><br>
	 *
	 * TODO: System currently only supports 1x1 block per data.
	 *
	 * @see    EDhApiDetailLevel
	 */
	default byte getSmallestDataDetailLevel() { return EDhApiDetailLevel.BLOCK.detailLevel; }
	/**
	 * Defines the largest datapoint size that can be generated at a time. <br>
	 * Minimum detail level is 0 (1 block) <br>
	 * Default detail level is 0 <br>
	 * For more information on what detail levels represent see: {@link EDhApiDetailLevel}.
	 *
	 * @see    EDhApiDetailLevel
	 */
	default byte getLargestDataDetailLevel() { return EDhApiDetailLevel.BLOCK.detailLevel; }
	
	/**
	 * When creating generation requests the system will attempt to group nearby tasks together. <br><br>
	 * What is the minimum size a single generation call can batch together? <br>
	 *
	 * Minimum detail level is 4 (the size of a MC chunk) <br>
	 * Default detail level is 4 <br>
	 * For more information on what detail levels represent see: {@link EDhApiDetailLevel}.
	 *
	 * @see    EDhApiDetailLevel
	 */
	default byte getMinGenerationGranularity() { return EDhApiDetailLevel.CHUNK.detailLevel; }
	
	/**
	 * When creating generation requests the system will attempt to group nearby tasks together. <br><br>
	 * What is the maximum size a single generation call can batch together? <br>
	 *
	 * Minimum detail level is 4 (the size of a MC chunk) <br>
	 * Default detail level is 6 (4x4 chunks) <br>
	 * For more information on what detail levels represent see: {@link EDhApiDetailLevel}.
	 *
	 * @see    EDhApiDetailLevel
	 */
	default byte getMaxGenerationGranularity() { return (byte) (EDhApiDetailLevel.CHUNK.detailLevel + 2); }
	
	/** Returns true if the generator is unable to accept new generation requests. */
	boolean isBusy();
	
	
	
	
	//=================//
	// world generator //
	//=================//
	
	/**
	 * This method is called by Distant Horizons to generate terrain over a given area. <br><br>
	 *
	 * After a chunk has been generated it (and any necessary supporting objects as listed below) should be passed into the
	 * resultConsumer's {@link Consumer#accept} method. If the Consumer is given the wrong data
	 * type(s) it will disable the world generator and log an error with a list of objects it was expecting. <br>
	 * <strong>Note:</strong> these objects are minecraft version dependent and will change without notice!
	 * Please run your generator in game at least once to confirm the objects you are returning are correct. <br><br>
	 *
	 * Consumer expected inputs for each minecraft version (in order): <br>
	 * <strong>1.18:</strong> [net.minecraft.world.level.chunk.ChunkAccess] and [net.minecraft.world.level.LevelReader] <br>
	 * <strong>1.19:</strong> [net.minecraft.world.level.chunk.ChunkAccess] and [net.minecraft.world.level.LevelReader] <br>
	 * <strong>1.20:</strong> [net.minecraft.world.level.chunk.ChunkAccess] and [net.minecraft.world.level.LevelReader] <br>
	 */
	CompletableFuture<Void> generateChunks(
			int chunkPosMinX, int chunkPosMinZ,
			byte granularity, byte targetDataDetail, EDhApiDistantGeneratorMode generatorMode,
			ExecutorService worldGeneratorThreadPool, Consumer<Object[]> resultConsumer);
	
	
	
	//===============//
	// event methods //
	//===============//
	
	/**
	 * Called before a new generator task is started. <br>
	 * This can be used to run cleanup on existing tasks before new tasks are started.
	 */
	void preGeneratorTaskStart();
	
	
	
	//===========//
	// overrides //
	//===========//
	
	// This is overridden to remove the "throws IOException" 
	// that is present in the default Closeable.close() method 
	@Override
	void close();
	
	
}
