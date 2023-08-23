package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.file.fullDatafile.IFullDataSourceProvider;
import com.seibel.distanthorizons.core.file.fullDatafile.RemoteFullDataFileHandler;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.generation.WorldRemoteGenerationQueue;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.multiplayer.ClientNetworkState;
import com.seibel.distanthorizons.core.network.NetworkClient;
import com.seibel.distanthorizons.core.network.ScopedNetworkEventSource;
import com.seibel.distanthorizons.core.network.messages.FullDataPartialUpdateMessage;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.coreapi.util.math.Mat4f;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.annotation.CheckForNull;
import java.awt.*;
import java.util.concurrent.CompletableFuture;

/** The level used when connected to a server */
public class DhClientLevel extends DhLevel implements IDhClientLevel
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	
	private static class WorldGenState extends WorldGenModule.WorldGenState
	{
		WorldGenState(IDhClientLevel level, ClientNetworkState networkState)
		{
			this.worldGenerationQueue = new WorldRemoteGenerationQueue(networkState, level);
		}
	}
	
	public final ClientLevelModule clientside;
	public final IClientLevelWrapper levelWrapper;
	public final AbstractSaveStructure saveStructure;
	public final RemoteFullDataFileHandler dataFileHandler;
	
	@CheckForNull
	private final ClientNetworkState networkState;
	@Nullable
	private final ScopedNetworkEventSource<NetworkClient> eventSource;
	public final WorldGenModule worldGenModule;
	
	

	//=============//
	// constructor //
	//=============//
	
	public DhClientLevel(AbstractSaveStructure saveStructure, IClientLevelWrapper clientLevelWrapper, @Nullable ClientNetworkState networkState)
	{
		this.levelWrapper = clientLevelWrapper;
		this.saveStructure = saveStructure;
		this.dataFileHandler = new RemoteFullDataFileHandler(this, saveStructure);
		
		this.networkState = networkState;
		this.worldGenModule = new WorldGenModule(dataFileHandler, this);
		if (networkState != null)
		{
			this.eventSource = new ScopedNetworkEventSource<>(networkState.getClient());
			this.registerNetworkHandlers();
		}
		else
		{
			this.eventSource = null;
		}
		
		clientside = new ClientLevelModule(this);
		clientside.startRenderer();
		LOGGER.info("Started DHLevel for " + this.levelWrapper + " with saves at " + this.saveStructure);
	}
	
	private void registerNetworkHandlers()
	{
		assert this.eventSource != null;
		
		this.eventSource.registerHandler(FullDataPartialUpdateMessage.class, msg ->
		{
			try
			{
				ChunkSizedFullDataAccessor fullDataAccessor = msg.getFullDataSource(this);
				if (fullDataAccessor == null) return;
				
				this.saveWrites(fullDataAccessor);
			}
			catch (Exception e)
			{
				LOGGER.error("Error while updating full data source", e);
			}
		});
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
	
	public void doWorldGen()
	{
		boolean isClientUsable = networkState != null && !networkState.getClient().isClosed();
		boolean shouldDoWorldGen = isClientUsable && clientside.isRendering();
		boolean isWorldGenRunning = worldGenModule.isWorldGenRunning();
		if (shouldDoWorldGen && !isWorldGenRunning)
		{
			// start world gen
			worldGenModule.startWorldGen(this.dataFileHandler, new WorldGenState(this, this.networkState));
		}
		else if (!shouldDoWorldGen && isWorldGenRunning)
		{
			// stop world gen
			worldGenModule.stopWorldGen(this.dataFileHandler);
		}
		
		if (worldGenModule.isWorldGenRunning())
		{
			ClientLevelModule.ClientRenderState renderState = clientside.ClientRenderStateRef.get();
			if (renderState != null && renderState.quadtree != null)
			{
				dataFileHandler.removeGenRequestIf(p -> !renderState.quadtree.isSectionPosInBounds(p));
			}
			worldGenModule.worldGenTick(new DhBlockPos2D(MC_CLIENT.getPlayerBlockPos()));
		}
	}

	@Override
	public void render(Mat4f mcModelViewMatrix, Mat4f mcProjectionMatrix, float partialTicks, IProfilerWrapper profiler)
	{
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
	public void clearRenderCache()
	{
		clientside.clearRenderCache();
	}
	
	@Override
	public ILevelWrapper getLevelWrapper() { return levelWrapper; }
	
	@Override
	public CompletableFuture<Void> saveAsync()
	{
		return CompletableFuture.allOf(clientside.saveAsync(), dataFileHandler.flushAndSave());
	}
	
	@Override
	public void saveWrites(ChunkSizedFullDataAccessor data)
	{
		clientside.saveWrites(data);
	}
	
	@Override
	public int getMinY() { return levelWrapper.getMinHeight(); }
	
	@Override
	public void close()
	{
		if (worldGenModule != null)
			worldGenModule.close();
		clientside.close();
		super.close();
		dataFileHandler.close();
		LOGGER.info("Closed " + DhClientLevel.class.getSimpleName() + " for " + levelWrapper);
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
	public IFullDataSourceProvider getFileHandler()
	{
		return dataFileHandler;
	}
	
	@Override
	public AbstractSaveStructure getSaveStructure()
	{
		return saveStructure;
	}
	
	@Override
	public void onWorldGenTaskComplete(DhSectionPos pos)
	{
		//if (pos.sectionDetailLevel == DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL)
		DebugRenderer.makeParticle(
				new DebugRenderer.BoxParticle(
						new DebugRenderer.Box(pos, 128f, 156f, 0.09f, Color.red.darker()),
						0.2, 32f
				)
		);
		clientside.reloadPos(pos);
	}
}
