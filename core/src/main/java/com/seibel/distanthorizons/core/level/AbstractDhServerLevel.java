package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.file.structure.ISaveStructure;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerState;
import com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerStateManager;
import com.seibel.distanthorizons.core.network.exceptions.InvalidLevelException;
import com.seibel.distanthorizons.core.network.exceptions.RequestRejectedException;
import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import com.seibel.distanthorizons.core.network.messages.AbstractTrackableMessage;
import com.seibel.distanthorizons.core.network.messages.ILevelRelatedMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataPartialUpdateMessage;
import com.seibel.distanthorizons.core.multiplayer.fullData.FullDataPayload;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.network.messages.requests.CancelMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.math.Vec3d;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

public abstract class AbstractDhServerLevel extends AbstractDhLevel implements IDhServerLevel
{
	protected static final Logger LOGGER = DhLoggerBuilder.getLogger();
	private static final ConfigBasedLogger NETWORK_LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Common.Logging.logNetworkEvent.get());
	
	/** 1 Mebibyte minus 576 bytes for other info */
	public static final int FULL_DATA_SPLIT_SIZE_IN_BYTES = 1_048_000;
	
	public final ServerLevelModule serverside;
	protected final IServerLevelWrapper serverLevelWrapper;
	
	protected final ServerPlayerStateManager serverPlayerStateManager;
	
	/**
	 * This queue is used for ensuring fair generation speed for each player. <br>
	 * Every tick the first player gets used for centering generation, and then is immediately moved into the back of the queue. <br>
	 * TODO only add players that actually have something to generate
	 */
	protected final ConcurrentLinkedQueue<IServerPlayerWrapper> worldGenPlayerCenteringQueue = new ConcurrentLinkedQueue<>();
	
	private final ConcurrentMap<Long, DataSourceRequestGroup> requestGroupByPos = new ConcurrentHashMap<>();
	private final ConcurrentMap<Long, DataSourceRequestGroup> requestGroupByFutureId = new ConcurrentHashMap<>();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public AbstractDhServerLevel(ISaveStructure saveStructure, IServerLevelWrapper serverLevelWrapper, ServerPlayerStateManager serverPlayerStateManager)
	{
		this(saveStructure, serverLevelWrapper, serverPlayerStateManager, true);
	}
	public AbstractDhServerLevel(
			ISaveStructure saveStructure,
			IServerLevelWrapper serverLevelWrapper,
			ServerPlayerStateManager serverPlayerStateManager,
			boolean runRepoReliantSetup
		)
	{
		if (saveStructure.getSaveFolder(serverLevelWrapper).mkdirs())
		{
			LOGGER.warn("unable to create data folder.");
		}
		this.serverLevelWrapper = serverLevelWrapper;
		this.serverside = new ServerLevelModule(this, saveStructure);
		this.createAndSetSupportingRepos(this.serverside.fullDataFileHandler.repo.databaseFile);
		if (runRepoReliantSetup)
		{
			this.runRepoReliantSetup();
		}
		
		LOGGER.info("Started "+this.getClass().getSimpleName()+" for ["+serverLevelWrapper+"] at ["+saveStructure+"].");
		
		this.serverPlayerStateManager = serverPlayerStateManager;
	}
	
	
	
	//=======//
	// ticks //
	//=======//
	
	@Override
	public void serverTick()
	{
		// Send finished data source requests
		for (Map.Entry<Long, DataSourceRequestGroup> entry : this.requestGroupByPos.entrySet())
		{
			DataSourceRequestGroup requestGroup = entry.getValue();
			
			if (requestGroup.fullDataSource == null)
			{
				continue;
			}
			
			NETWORK_LOGGER.debug("["+this.serverLevelWrapper.getDimensionName()+"] Fulfilled request group ["+entry.getKey()+"]");
			
			// Make this group unavailable for adding into
			this.requestGroupByPos.remove(entry.getKey());
			requestGroup.requestRemoveSemaphore.acquireUninterruptibly(Short.MAX_VALUE);
			requestGroup.requestAddSemaphore.acquireUninterruptibly(Short.MAX_VALUE);
			
			ThreadPoolExecutor executor = ThreadPoolUtil.getNetworkCompressionExecutor();
			if (executor == null)
			{
				LOGGER.warn("Unable to send FullDataSourceResponseMessage - getNetworkCompressionExecutor() is null");
				continue;
			}
			CompletableFuture.runAsync(() ->
			{
				Objects.requireNonNull(this.beaconBeamRepo);
				try (FullDataPayload payload = new FullDataPayload(requestGroup.fullDataSource, this.beaconBeamRepo.getAllBeamsForPos(entry.getKey())))
				{
					for (FullDataSourceRequestMessage msg : requestGroup.requestMessages.values())
					{
						this.requestGroupByFutureId.remove(msg.futureId);
						
						ServerPlayerState serverPlayerState = this.serverPlayerStateManager.getConnectedPlayer(msg.serverPlayer());
						if (serverPlayerState == null)
						{
							continue;
						}
						
						serverPlayerState.fullDataPayloadSender.sendInChunks(payload, () -> {
							msg.sendResponse(new FullDataSourceResponseMessage(payload));
							serverPlayerState.getRateLimiterSet(this).generationRequestRateLimiter.release();
						});
					}
				}
			}, executor);
		}
	}
	
	@Override
	public boolean shouldDoWorldGen()
	{ return Config.Common.WorldGenerator.enableDistantGeneration.get() && !this.worldGenPlayerCenteringQueue.isEmpty(); }
	
	@Override
	@Nullable
	public DhBlockPos2D getTargetPosForGeneration()
	{
		IServerPlayerWrapper firstPlayer = this.worldGenPlayerCenteringQueue.peek();
		if (firstPlayer == null)
		{
			return null;
		}
		
		// Put first player in back before removing from front, so it can be removed by other thread without blocking
		// - if it gets removed, remove() below will remove the item we just put instead
		this.worldGenPlayerCenteringQueue.add(firstPlayer);
		this.worldGenPlayerCenteringQueue.remove(firstPlayer);
		
		Vec3d position = firstPlayer.getPosition();
		return new DhBlockPos2D((int) position.x, (int) position.z);
	}
	
	@Override 
	public void worldGenTick() { this.serverside.worldGenModule.worldGenTick(); }
	
	
	
	//==================//
	// network handling //
	//==================//
	
	public void registerNetworkHandlers(ServerPlayerState serverPlayerState)
	{
		serverPlayerState.networkSession.registerHandler(FullDataSourceRequestMessage.class, (message) ->
		{
			if (!this.messagePlayerInThisLevel(message))
			{
				// we can't handle players in other levels, don't continue
				return;
			}
			
			
			ServerPlayerState.RateLimiterSet rateLimiterSet = serverPlayerState.getRateLimiterSet(this);
			
			if (message.clientTimestamp == null)
			{
				this.queueWorldGenForRequestMessage(serverPlayerState, message, rateLimiterSet);
			}
			else
			{
				this.queueLodSyncForRequestMessage(serverPlayerState, message, rateLimiterSet);
			}
		});
		
		
		serverPlayerState.networkSession.registerHandler(CancelMessage.class, msg ->
		{
			DataSourceRequestGroup requestGroup = this.requestGroupByFutureId.remove(msg.futureId);
			if (requestGroup == null)
			{
				return;
			}
			
			// If this fails, the group is being removed and completing cancellation is not necessary
			if (requestGroup.requestRemoveSemaphore.tryAcquire())
			{
				// Prevent adding requests in case the group will be removed by this cancellation
				requestGroup.requestAddSemaphore.acquireUninterruptibly(Short.MAX_VALUE);
				requestGroup.requestRemoveSemaphore.release();
				
				serverPlayerState.getRateLimiterSet(this).generationRequestRateLimiter.release();
				
				FullDataSourceRequestMessage requestMessage = requestGroup.requestMessages.remove(msg.futureId);
				if (requestGroup.requestMessages.isEmpty())
				{
					NETWORK_LOGGER.debug("["+this.serverLevelWrapper.getDimensionName()+"] Cancelled request group ["+DhSectionPos.toString(requestMessage.sectionPos)+"].");
					this.requestGroupByPos.remove(requestMessage.sectionPos);
					this.serverside.fullDataFileHandler.removeRetrievalRequestIf(pos -> pos == requestMessage.sectionPos);
				}
				else
				{
					requestGroup.requestAddSemaphore.release(Short.MAX_VALUE);
				}
			}
		});
	}
	private void queueLodSyncForRequestMessage(ServerPlayerState serverPlayerState, FullDataSourceRequestMessage message, ServerPlayerState.RateLimiterSet rateLimiterSet)
	{
		if (!serverPlayerState.sessionConfig.getSynchronizeOnLoad())
		{
			message.sendResponse(new RequestRejectedException("Operation is disabled in config."));
			return;
		}
		
		if (!rateLimiterSet.syncOnLoginRateLimiter.tryAcquire(message))
		{
			return;
		}
		
		
		// the client timestamp will be null if we want to retrieve the LOD regardless of when it was last updated
		long clientTimestamp = (message.clientTimestamp != null) ? message.clientTimestamp : -1;
		// the server timestamp will be null if no LOD data exists for this position
		Long serverTimestamp = this.serverside.fullDataFileHandler.getTimestampForPos(message.sectionPos);
		if (serverTimestamp == null
				|| serverTimestamp <= clientTimestamp)
		{
			// either no data exists to sync, or the client is already up to date
			rateLimiterSet.syncOnLoginRateLimiter.release();
			message.sendResponse(new FullDataSourceResponseMessage(null));
			return;
		}
		
		
		
		ThreadPoolExecutor executor = ThreadPoolUtil.getNetworkCompressionExecutor();
		if (executor == null)
		{
			// shouldn't normally happen, but just in case
			LOGGER.warn("Unable to send FullDataSourceResponseMessage - getNetworkCompressionExecutor() is null");
			return;
		}
		
		this.serverside.fullDataFileHandler.getAsync(message.sectionPos).thenAcceptAsync(fullDataSource ->
		{
			Objects.requireNonNull(this.beaconBeamRepo);
			try (FullDataPayload payload = new FullDataPayload(fullDataSource, this.beaconBeamRepo.getAllBeamsForPos(message.sectionPos)))
			{
				serverPlayerState.fullDataPayloadSender.sendInChunks(payload, () -> {
					message.sendResponse(new FullDataSourceResponseMessage(payload));
					rateLimiterSet.syncOnLoginRateLimiter.release();
				});
			}
		}, executor);
	}
	private void queueWorldGenForRequestMessage(ServerPlayerState serverPlayerState, FullDataSourceRequestMessage message, ServerPlayerState.RateLimiterSet rateLimiterSet)
	{
		if (!serverPlayerState.sessionConfig.isDistantGenerationEnabled())
		{
			message.sendResponse(new RequestRejectedException("Operation is disabled in config."));
			return;
		}
		
		if (!rateLimiterSet.generationRequestRateLimiter.tryAcquire(message))
		{
			return;
		}
		
		while (true)
		{
			DataSourceRequestGroup requestGroup = this.requestGroupByPos.computeIfAbsent(message.sectionPos, pos ->
			{
				DataSourceRequestGroup newGroup = new DataSourceRequestGroup();
				this.tryFulfillDataSourceRequestGroup(newGroup, pos);
				NETWORK_LOGGER.debug("["+this.serverLevelWrapper.getDimensionName()+"] Created request group for pos ["+DhSectionPos.toString(pos)+"].");
				return newGroup;
			});
			
			// If this fails, loop until either a permit is acquired or the group is removed to create another one
			if (!requestGroup.requestAddSemaphore.tryAcquire())
			{
				Thread.yield();
				continue;
			}
			
			this.requestGroupByFutureId.put(message.futureId, requestGroup);
			requestGroup.requestMessages.put(message.futureId, message);
			requestGroup.requestAddSemaphore.release();
			break;
		}
	}
	
	
	/** May send an error message in response if the message is a {@link AbstractTrackableMessage} */
	private <T extends AbstractNetworkMessage> boolean messagePlayerInThisLevel(T message)
	{
		if (!(message instanceof ILevelRelatedMessage))
		{
			LodUtil.assertNotReach("Received message ["+message+"] does not implement ["+ILevelRelatedMessage.class.getSimpleName()+"]");
		}
		
		// Only handle requests for this level
		if (!((ILevelRelatedMessage) message).isSameLevelAs(this.getServerLevelWrapper()))
		{
			return false;
		}
		
		LodUtil.assertTrue(message.getSession().serverPlayer != null);
		
		// Check if the player is in this dimension,
		// since handling multiple dimensions isn't allowed
		if (message.getSession().serverPlayer.getLevel() != this.getLevelWrapper())
		{
			// If the message can be replied to - reply with an error, otherwise just ignore
			if (message instanceof AbstractTrackableMessage)
			{
				((AbstractTrackableMessage) message).sendResponse(
						new InvalidLevelException(
								"Generation not allowed. " +
										"Requested dimension: ["+((ILevelRelatedMessage) message).getLevelName()+"], " +
										"player dimension: ["+message.getSession().serverPlayer.getLevel().getDimensionName()+"], " +
										"handler dimension: ["+this.getLevelWrapper().getDimensionName()+"]"
						)
				);
			}
			
			return false;
		}
		
		return true;
	}
	
	
	
	//===========//
	// world gen //
	//===========//
	
	@Override
	public void onWorldGenTaskComplete(long pos)
	{
		DataSourceRequestGroup requestGroup = this.requestGroupByPos.get(pos);
		if (requestGroup != null)
		{
			requestGroup.worldGenTaskComplete = true;
			this.tryFulfillDataSourceRequestGroup(requestGroup, pos);
		}
	}
	
	private void tryFulfillDataSourceRequestGroup(DataSourceRequestGroup requestGroup, long pos)
	{
		this.serverside.fullDataFileHandler.getAsync(pos).thenAccept(fullDataSource ->
		{
			if (this.serverside.fullDataFileHandler.isFullyGenerated(fullDataSource.columnGenerationSteps))
			{
				requestGroup.fullDataSource = fullDataSource;
			}
			else if (requestGroup.worldGenTaskComplete)
			{
				// If the returned data source is not fully generated, try reading it again
				this.tryFulfillDataSourceRequestGroup(requestGroup, pos);
			}
			else
			{
				this.serverside.fullDataFileHandler.queuePositionForRetrieval(pos);
			}
		});
	}
	
	
	
	//=================//
	// player handling //
	//=================//
	
	public void addPlayer(IServerPlayerWrapper serverPlayer) { this.worldGenPlayerCenteringQueue.add(serverPlayer); }
	public void removePlayer(IServerPlayerWrapper serverPlayer) { this.worldGenPlayerCenteringQueue.remove(serverPlayer); }
	
	@Override
	public CompletableFuture<Void> updateDataSourcesAsync(FullDataSourceV2 data)
	{
		if (!Config.Server.enableRealTimeUpdates.get())
		{
			return this.getFullDataProvider().updateDataSourceAsync(data);
		}
		
		ThreadPoolExecutor executor = ThreadPoolUtil.getNetworkCompressionExecutor();
		if (executor == null)
		{
			LOGGER.warn("Unable to send FullDataPartialUpdateMessage - getNetworkCompressionExecutor() is null");
			return this.getFullDataProvider().updateDataSourceAsync(data);
		}
		CompletableFuture.runAsync(() ->
		{
			Objects.requireNonNull(this.beaconBeamRepo);
			try (FullDataPayload payload = new FullDataPayload(data, this.beaconBeamRepo.getAllBeamsForPos(data.getPos())))
			{
				for (ServerPlayerState serverPlayerState : this.serverPlayerStateManager.getReadyPlayers())
				{
					if (serverPlayerState.getServerPlayer().getLevel() != this.serverLevelWrapper)
					{
						continue;
					}
					
					if (!serverPlayerState.sessionConfig.isRealTimeUpdatesEnabled())
					{
						continue;
					}
					
					Vec3d playerPosition = serverPlayerState.getServerPlayer().getPosition();
					int distanceFromPlayer = DhSectionPos.getChebyshevBlockDistance(data.getPos(), new DhBlockPos2D((int) playerPosition.x, (int) playerPosition.z)) / 16;
					if (distanceFromPlayer >= serverPlayerState.getServerPlayer().getViewDistance()
							&& distanceFromPlayer <= serverPlayerState.sessionConfig.getMaxUpdateDistanceRadius())
					{
						serverPlayerState.fullDataPayloadSender.sendInChunks(payload, () ->
						{
							serverPlayerState.networkSession.sendMessage(new FullDataPartialUpdateMessage(this.serverLevelWrapper, payload));
						});
					}
				}
			}
		}, executor);
		
		
		return this.getFullDataProvider().updateDataSourceAsync(data);
	}
	
	
	
	//===========//
	// debugging //
	//===========//
	
	@Override
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		// migration
		boolean migrationErrored = this.serverside.fullDataFileHandler.getMigrationStoppedWithError();
		if (!migrationErrored)
		{
			long legacyDeletionCount = this.serverside.fullDataFileHandler.getLegacyDeletionCount();
			if (legacyDeletionCount > 0)
			{
				messageList.add("  Migrating - Deleting #: " + F3Screen.NUMBER_FORMAT.format(legacyDeletionCount));
			}
			long migrationCount = this.serverside.fullDataFileHandler.getTotalMigrationCount();
			if (migrationCount > 0)
			{
				messageList.add("  Migrating - Conversion #: " + F3Screen.NUMBER_FORMAT.format(migrationCount));
			}
		}
		else
		{
			messageList.add("  Migration Failed");
		}
		
		// world gen
		this.serverside.worldGenModule.addDebugMenuStringsToList(messageList);
	}
	
	
	
	//=========//
	// getters //
	//=========//
	
	@Override
	public int getMinY() { return this.getLevelWrapper().getMinHeight(); }
	
	@Override
	public IServerLevelWrapper getServerLevelWrapper() { return this.serverLevelWrapper; }
	
	@Override
	public ILevelWrapper getLevelWrapper() { return this.getServerLevelWrapper(); }
	
	@Override
	public FullDataSourceProviderV2 getFullDataProvider() { return this.serverside.fullDataFileHandler; }
	
	@Override
	public ISaveStructure getSaveStructure() { return this.serverside.saveStructure; }
	
	@Override
	public boolean hasSkyLight() { return this.serverLevelWrapper.hasSkyLight(); }
	
	
	
	//==========//
	// shutdown //
	//==========//
	
	@Override
	public void close()
	{
		super.close();
		this.serverside.close();
		LOGGER.info("Closed DHLevel for [" + this.getLevelWrapper() + "].");
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	private static class DataSourceRequestGroup
	{
		public final ConcurrentMap<Long, FullDataSourceRequestMessage> requestMessages = new ConcurrentHashMap<>();
		
		/** If this variable is true, we definitely know that generation is complete and there's no need for checking column gen steps */
		boolean worldGenTaskComplete = false;
		
		@CheckForNull
		public FullDataSourceV2 fullDataSource = null;
		
		/**
		 * These two Semaphores are used to prevent all threads from locking on the group after it being fulfilled,
		 * as opposed to ReentrantReadWriteLocks which would allow the locking thread continue using it anyway. <br>
		 * Short.MAX_VALUE is chosen as a large enough number so non-exclusive accesses never block each other.
		 */
		public final Semaphore requestAddSemaphore = new Semaphore(Short.MAX_VALUE, true);
		/** @see DataSourceRequestGroup#requestAddSemaphore */
		public final Semaphore requestRemoveSemaphore = new Semaphore(Short.MAX_VALUE, true);
		
	}
	
}
