package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.core.config.AppliedConfigState;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IIncompleteFullDataSource;
import com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataFileHandler;
import com.seibel.distanthorizons.core.file.fullDatafile.IFullDataSourceProvider;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.multiplayer.RemotePlayer;
import com.seibel.distanthorizons.core.multiplayer.RemotePlayerConnectionHandler;
import com.seibel.distanthorizons.core.network.ChildNetworkEventSource;
import com.seibel.distanthorizons.core.network.NetworkServer;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.messages.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.world.DhServerWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.coreapi.util.math.Vec3d;
import org.apache.logging.log4j.Logger;

import javax.annotation.CheckForNull;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.*;

public class DhServerLevel extends DhLevel implements IDhServerLevel
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	public final ServerLevelModule serverside;
	private final IServerLevelWrapper serverLevelWrapper;
	
	private final RemotePlayerConnectionHandler remotePlayerConnectionHandler;
	private final ChildNetworkEventSource<NetworkServer> eventSource;
	
	private final ConcurrentLinkedQueue<IServerPlayerWrapper> worldGenLoopingQueue = new ConcurrentLinkedQueue<>();
	private final ConcurrentMap<DhSectionPos, IncompleteDataSourceEntry> incompleteDataSources = new ConcurrentHashMap<>();
	private final AppliedConfigState<Integer> rateLimitConfig = new AppliedConfigState<>(Config.Client.Advanced.Multiplayer.serverNetworkingRateLimit);
	
	
	public DhServerLevel(AbstractSaveStructure saveStructure, IServerLevelWrapper serverLevelWrapper, RemotePlayerConnectionHandler remotePlayerConnectionHandler)
	{
		if (saveStructure.getFullDataFolder(serverLevelWrapper).mkdirs())
		{
			LOGGER.warn("unable to create data folder.");
		}
		this.serverLevelWrapper = serverLevelWrapper;
		serverside = new ServerLevelModule(this, serverLevelWrapper, saveStructure);
		LOGGER.info("Started DHLevel for {} with saves at {}", serverLevelWrapper, saveStructure);
	
		this.remotePlayerConnectionHandler = remotePlayerConnectionHandler;
		this.eventSource = new ChildNetworkEventSource<>(remotePlayerConnectionHandler.eventSource);
		this.registerNetworkHandlers();
	}
	
	private void registerNetworkHandlers()
	{
		this.eventSource.registerHandler(FullDataSourceRequestMessage.class, msg ->
		{
			RemotePlayer remotePlayer = remotePlayerConnectionHandler.getConnectedPlayer(msg);
			if (remotePlayer.serverPlayer.getLevel() != this.serverLevelWrapper)
				return;
			
			LOGGER.info("FullDataSourceRequestMessage received at pos ({}, {}) with detail level {}", msg.dhSectionPos.sectionX, msg.dhSectionPos.sectionZ, msg.dhSectionPos.sectionDetailLevel);
			
			if (remotePlayer.pendingFullDataRequests.incrementAndGet() > rateLimitConfig.get())
			{
				remotePlayer.pendingFullDataRequests.decrementAndGet();
				msg.sendResponse(new RateLimitedException("Max concurrent requests: "+rateLimitConfig.get()));
				return;
			}
			
			while (true)
			{
				IncompleteDataSourceEntry entry = incompleteDataSources.computeIfAbsent(msg.dhSectionPos, pos -> {
					IncompleteDataSourceEntry newEntry = new IncompleteDataSourceEntry();
					serverside.dataFileHandler.read(msg.dhSectionPos).thenAccept(fullDataSource -> {
						newEntry.fullDataSource = fullDataSource;
					});
					return newEntry;
				});
				// If this fails, current entry is being drained and need create another one
				if (entry.requestCollectionSemaphore.tryAcquire())
				{
					entry.requestMessages.add(msg);
					entry.requestCollectionSemaphore.release();
					break;
				}
			}
		});
	}
	
	public void addPlayer(IServerPlayerWrapper serverPlayer)
	{
		synchronized (worldGenLoopingQueue)
		{
			this.worldGenLoopingQueue.add(serverPlayer);
		}
	}
	
	public void removePlayer(IServerPlayerWrapper serverPlayer)
	{
		synchronized (worldGenLoopingQueue)
		{
			this.worldGenLoopingQueue.remove(serverPlayer);
		}
	}
	
	public void serverTick()
	{
		chunkToLodBuilder.tick();
		
		for (Iterator<IncompleteDataSourceEntry> it = incompleteDataSources.values().iterator(); it.hasNext(); )
		{
			IncompleteDataSourceEntry entry = it.next();
			if (entry.fullDataSource == null) continue;
			
			if (entry.fullDataSource instanceof IIncompleteFullDataSource)
			{
				IIncompleteFullDataSource incompleteSource = (IIncompleteFullDataSource) entry.fullDataSource;
				if (!incompleteSource.hasBeenPromoted()) continue;
				entry.fullDataSource = incompleteSource.tryPromotingToCompleteDataSource();
			}
			
			LodUtil.assertTrue(entry.fullDataSource instanceof CompleteFullDataSource, "Invalid full data source");
			
			it.remove();
			// This semaphore is intentionally acquired forever
			entry.requestCollectionSemaphore.acquireUninterruptibly(Short.MAX_VALUE);
			
			CompleteFullDataSource completeSource = (CompleteFullDataSource) entry.fullDataSource;
			for (FullDataSourceRequestMessage msg : entry.requestMessages)
			{
				RemotePlayer remotePlayer = remotePlayerConnectionHandler.getConnectedPlayer(msg);
				if (remotePlayer == null) continue;
				remotePlayer.pendingFullDataRequests.decrementAndGet();
				
				msg.sendResponse(new FullDataSourceResponseMessage(completeSource, this));
			}
		}
	}

	@Override
	public void saveWrites(ChunkSizedFullDataAccessor data) {
		DhLodPos pos = data.getLodPos().convertToDetailLevel(CompleteFullDataSource.SECTION_SIZE_OFFSET);
		getFileHandler().write(new DhSectionPos(pos.detailLevel, pos.x, pos.z), data);
	}

	@Override
	public int getMinY() { return getLevelWrapper().getMinHeight(); }
	
	@Override
	public void dumpRamUsage()
	{
		//TODO
	}
	
	@Override
	public void close()
	{
		super.close();
		serverside.close();
		LOGGER.info("Closed DHLevel for {}", getLevelWrapper());
	}
	
	@Override
	public CompletableFuture<Void> saveAsync() { return getFileHandler().flushAndSave(); }
	
	@Override
	public void doWorldGen()
	{
		boolean shouldDoWorldGen = true; //todo;
		boolean isWorldGenRunning = serverside.worldGenModule.isWorldGenRunning();
		if (shouldDoWorldGen && !isWorldGenRunning)
		{
			// start world gen
			serverside.worldGenModule.startWorldGen(serverside.dataFileHandler, new ServerLevelModule.WorldGenState(this));
		}
		else if (!shouldDoWorldGen && isWorldGenRunning)
		{
			// stop world gen
			serverside.worldGenModule.stopWorldGen(serverside.dataFileHandler);
		}
		
		if (serverside.worldGenModule.isWorldGenRunning())
		{
			IServerPlayerWrapper firstPlayer;
			synchronized (worldGenLoopingQueue)
			{
				firstPlayer = this.worldGenLoopingQueue.poll();
				if (firstPlayer == null)
					return;
				this.worldGenLoopingQueue.add(firstPlayer);
			}
			
			Vec3d position = firstPlayer.getPosition();
			serverside.worldGenModule.worldGenTick(new DhBlockPos2D((int) position.x, (int) position.z));
		}
	}
	
	@Override
	public IServerLevelWrapper getServerLevelWrapper() { return serverLevelWrapper; }
	
	@Override
	public ILevelWrapper getLevelWrapper() { return getServerLevelWrapper(); }
	
	@Override
	public IFullDataSourceProvider getFileHandler() { return serverside.dataFileHandler; }

	@Override
	public AbstractSaveStructure getSaveStructure() {
		return serverside.saveStructure;
	}

	@Override
	public void onWorldGenTaskComplete(DhSectionPos pos) {
		//TODO: Send packet to client
	}
	
	private static class IncompleteDataSourceEntry
	{
		@CheckForNull
		public IFullDataSource fullDataSource;
		public final Set<FullDataSourceRequestMessage> requestMessages = ConcurrentHashMap.newKeySet();
		public final Semaphore requestCollectionSemaphore = new Semaphore(Short.MAX_VALUE, true);
	}
}
