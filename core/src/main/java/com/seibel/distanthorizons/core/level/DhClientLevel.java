package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.file.fullDatafile.IFullDataSourceProvider;
import com.seibel.distanthorizons.core.file.fullDatafile.RemoteFullDataFileHandler;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.ChildNetworkEventSource;
import com.seibel.distanthorizons.core.network.NetworkClient;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.coreapi.util.math.Mat4f;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CompletableFuture;

/** The level used when connected to a server */
public class DhClientLevel extends DhLevel implements IDhClientLevel
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();

	public final ClientLevelModule clientside;
	public final IClientLevelWrapper levelWrapper;
	public final AbstractSaveStructure saveStructure;
	public final RemoteFullDataFileHandler dataFileHandler;

	//=============//
	// constructor //
	//=============//
	
	public DhClientLevel(AbstractSaveStructure saveStructure, IClientLevelWrapper clientLevelWrapper, ChildNetworkEventSource<NetworkClient> eventSource)
	{
		this.levelWrapper = clientLevelWrapper;
		this.saveStructure = saveStructure;
		dataFileHandler = new RemoteFullDataFileHandler(this, saveStructure, eventSource);
		clientside = new ClientLevelModule(this);
		clientside.startRenderer();
		LOGGER.info("Started DHLevel for "+this.levelWrapper+" with saves at "+this.saveStructure);
	}

	//==============//
	// tick methods //
	//==============//
	
	@Override
	public void clientTick()
	{
		chunkToLodBuilder.tick();
		clientside.clientTick();
	}

	@Override
	public void render(Mat4f mcModelViewMatrix, Mat4f mcProjectionMatrix, float partialTicks, IProfilerWrapper profiler) {
		clientside.render(mcModelViewMatrix, mcProjectionMatrix, partialTicks, profiler);
	}

	//================//
	// level handling //
	//================//
	
	@Override
	public int computeBaseColor(DhBlockPos pos, IBiomeWrapper biome, IBlockStateWrapper block) { return levelWrapper.computeBaseColor(pos, biome, block); }
	
	@Override
	public IClientLevelWrapper getClientLevelWrapper() { return levelWrapper; }

	@Override
	public void clearRenderCache() {
		clientside.clearRenderCache();
	}

	@Override
	public ILevelWrapper getLevelWrapper() { return levelWrapper; }

	@Override
	public CompletableFuture<Void> saveAsync() {
		return CompletableFuture.allOf(clientside.saveAsync(), dataFileHandler.flushAndSave());
	}

	@Override
	public void saveWrites(ChunkSizedFullDataAccessor data) {
		clientside.saveWrites(data);
	}

	@Override
	public int getMinY() { return levelWrapper.getMinHeight(); }

	@Override
	public void close()
	{
		clientside.close();
		super.close();
		dataFileHandler.close();
		LOGGER.info("Closed "+DhClientLevel.class.getSimpleName()+" for "+levelWrapper);
	}

	//=======================//
	// misc helper functions //
	//=======================//
	
	@Override
	public void dumpRamUsage()
	{
		//TODO
	}
	
	@Override
	public IFullDataSourceProvider getFileHandler() {
		return dataFileHandler;
	}

	@Override
	public AbstractSaveStructure getSaveStructure() {
		return saveStructure;
	}

}
