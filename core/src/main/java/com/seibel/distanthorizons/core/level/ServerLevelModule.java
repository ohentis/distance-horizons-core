package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.core.config.AppliedConfigState;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataFileHandler;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.generation.BatchGenerator;
import com.seibel.distanthorizons.core.generation.WorldGenerationQueue;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.WorldGeneratorInjector;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class ServerLevelModule
{
	public static class WorldGenState extends WorldGenModule.WorldGenState
	{
		WorldGenState(IDhServerLevel level)
		{
			IDhApiWorldGenerator worldGenerator = WorldGeneratorInjector.INSTANCE.get(level.getLevelWrapper());
			if (worldGenerator == null)
			{
				// no override generator is bound, use the Core world generator
				worldGenerator = new BatchGenerator(level);
				// binding the core generator won't prevent other mods from binding their own generators
				// since core world generator's should have the lowest override priority
				WorldGeneratorInjector.INSTANCE.bind(level.getLevelWrapper(), worldGenerator);
			}
			this.worldGenerationQueue = new WorldGenerationQueue(worldGenerator);
		}
		
	}
	
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	public final IServerLevelWrapper levelWrapper;
	public final IDhServerLevel parent;
	public final AbstractSaveStructure saveStructure;
	public final GeneratedFullDataFileHandler dataFileHandler;
	public final AppliedConfigState<Boolean> worldGeneratorEnabledConfig;
	public final WorldGenModule worldGenModule;
	
	public ServerLevelModule(IDhServerLevel parent, IServerLevelWrapper levelWrapper, AbstractSaveStructure saveStructure)
	{
		this.parent = parent;
		this.levelWrapper = levelWrapper;
		this.saveStructure = saveStructure;
		this.dataFileHandler = new GeneratedFullDataFileHandler(parent, saveStructure);
		this.worldGeneratorEnabledConfig = new AppliedConfigState<>(Config.Client.Advanced.WorldGenerator.enableDistantGeneration);
		this.worldGenModule = new WorldGenModule(this.dataFileHandler, this.parent);
	}
	
	//===============//
	// data handling //
	//===============//
	public void close()
	{
		// shutdown the world-gen
		this.worldGenModule.close();
		dataFileHandler.close();
	}
	
	
	
	//=======================//
	// misc helper functions //
	//=======================//
	
	public void dumpRamUsage()
	{
		//TODO
	}
	
}
