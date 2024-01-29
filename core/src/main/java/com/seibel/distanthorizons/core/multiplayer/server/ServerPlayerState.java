package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.network.IConnection;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.messages.fullData.generation.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.generation.priority.GenTaskPriorityRequestMessage;
import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedRateAndConcurrencyLimiter;
import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedRateLimiter;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import org.jetbrains.annotations.NotNull;

import static com.seibel.distanthorizons.core.config.Config.Client.Advanced.Multiplayer.ServerNetworking;

public class ServerPlayerState
{	
    public IServerPlayerWrapper serverPlayer;
    public IConnection connection;
	
	@NotNull
	public ServersideMultiplayerConfig config = new ServersideMultiplayerConfig();
	
	public final SupplierBasedRateLimiter<Void> rateLimitKickTrigger = new SupplierBasedRateLimiter<>(
			() -> ServerNetworking.rateLimitHitTolerance.get(),
			ignored -> this.connection.disconnect("You have been repeatedly exceeding rate/concurrency limits.")
	);
	
	public final SupplierBasedRateAndConcurrencyLimiter<FullDataSourceRequestMessage> fullDataRequestConcurrencyLimiter = new SupplierBasedRateAndConcurrencyLimiter<>(
			() -> ServerNetworking.generationRequestRCLimit.get(),
		    msg -> {
			    msg.sendResponse(new RateLimitedException("Full data request rate/concurrency limit: " + this.config.getFullDataRequestConcurrencyLimit()));
				this.rateLimitKickTrigger.tryAcquire(null);
		    }
	);
	
	public final SupplierBasedRateLimiter<GenTaskPriorityRequestMessage> genTaskPriorityRequestRateLimiter = new SupplierBasedRateLimiter<>(
			() -> ServerNetworking.genTaskPriorityRequestRateLimit.get(),
			msg -> {
				msg.sendResponse(new RateLimitedException("Generation task priority check rate limit: " + this.config.getFullDataRequestConcurrencyLimit()));
				this.rateLimitKickTrigger.tryAcquire(null);
			}
	);
	
	public final SupplierBasedRateAndConcurrencyLimiter<FullDataSourceRequestMessage> loginDataSyncRCLimiter = new SupplierBasedRateAndConcurrencyLimiter<>(
			() -> ServerNetworking.loginDataSyncRCLimit.get(),
			msg -> {
				msg.sendResponse(new RateLimitedException("Data sync rate/concurrency limit: " + this.config.getLoginDataSyncRCLimit()));
				this.rateLimitKickTrigger.tryAcquire(null);
			}
	);
	
	
	
	public ServerPlayerState(IServerPlayerWrapper serverPlayer) { this.serverPlayer = serverPlayer; }
    
}

