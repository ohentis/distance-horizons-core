package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.network.netty.INettyConnection;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.messages.netty.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.netty.fullData.generation.GenTaskPriorityRequestMessage;
import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedRateAndConcurrencyLimiter;
import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedRateLimiter;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentHashMap;

import static com.seibel.distanthorizons.core.config.Config.Client.Advanced.Multiplayer.ServerNetworking;

public class ServerPlayerState
{	
    public IServerPlayerWrapper serverPlayer;
	public INettyConnection connection;
	
	@NotNull
	public ConstrainedMultiplayerConfig config = new ConstrainedMultiplayerConfig();
	
	public final SupplierBasedRateLimiter<Void> rateLimitKickTrigger = new SupplierBasedRateLimiter<>(
			() -> ServerNetworking.rateLimitHitTolerance.get(),
			ignored -> this.connection.disconnect("You have been repeatedly exceeding rate/concurrency limits.")
	);
	
	private final ConcurrentHashMap<DhServerLevel, RateLimiterSet> rateLimiterSets = new ConcurrentHashMap<>();
	public RateLimiterSet getRateLimiterSet(DhServerLevel level)
	{
		return this.rateLimiterSets.computeIfAbsent(level, ignored -> new RateLimiterSet());
	}
	
	public ServerPlayerState(IServerPlayerWrapper serverPlayer) { this.serverPlayer = serverPlayer; }
	
	
	public class RateLimiterSet
	{
		public final SupplierBasedRateAndConcurrencyLimiter<FullDataSourceRequestMessage> fullDataRequestConcurrencyLimiter = new SupplierBasedRateAndConcurrencyLimiter<>(
				() -> ServerNetworking.generationRequestRCLimit.get(),
				msg -> {
					msg.sendResponse(new RateLimitedException("Full data request rate/concurrency limit: " + ServerPlayerState.this.config.getFullDataRequestConcurrencyLimit()));
					ServerPlayerState.this.rateLimitKickTrigger.tryAcquire(null);
				}
		);
		
		public final SupplierBasedRateLimiter<GenTaskPriorityRequestMessage> genTaskPriorityRequestRateLimiter = new SupplierBasedRateLimiter<>(
				() -> ServerNetworking.genTaskPriorityRequestRateLimit.get(),
				msg -> {
					msg.sendResponse(new RateLimitedException("Generation task priority check rate limit: " + ServerPlayerState.this.config.getFullDataRequestConcurrencyLimit()));
					ServerPlayerState.this.rateLimitKickTrigger.tryAcquire(null);
				}
		);
		
		public final SupplierBasedRateAndConcurrencyLimiter<FullDataSourceRequestMessage> loginDataSyncRCLimiter = new SupplierBasedRateAndConcurrencyLimiter<>(
				() -> ServerNetworking.loginDataSyncRCLimit.get(),
				msg -> {
					msg.sendResponse(new RateLimitedException("Data sync rate/concurrency limit: " + ServerPlayerState.this.config.getLoginDataSyncRCLimit()));
					ServerPlayerState.this.rateLimitKickTrigger.tryAcquire(null);
				}
		);
		
	}
	
}

