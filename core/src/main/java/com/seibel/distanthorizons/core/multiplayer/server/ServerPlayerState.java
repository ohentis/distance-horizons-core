package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.network.IConnection;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.messages.fullData.generation.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.generation.priority.GenTaskPriorityRequestMessage;
import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedConcurrencyLimiter;
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
	
    public final SupplierBasedConcurrencyLimiter<FullDataSourceRequestMessage> fullDataRequestConcurrencyLimiter = new SupplierBasedConcurrencyLimiter<>(
			() -> ServerNetworking.fullDataRequestConcurrencyLimit.get(),
		    msg -> {
			    msg.sendResponse(new RateLimitedException("Max concurrent full data requests: " + this.config.getFullDataRequestConcurrencyLimit()));
				this.rateLimitKickTrigger.tryAcquire(null);
		    }
	);
	
	public final SupplierBasedRateLimiter<GenTaskPriorityRequestMessage> genTaskPriorityRequestRateLimiter = new SupplierBasedRateLimiter<>(
			() -> ServerNetworking.genTaskPriorityRequestRateLimit.get(),
			msg -> {
				msg.sendResponse(new RateLimitedException("Max section checks per second: " + this.config.getFullDataRequestConcurrencyLimit()));
				this.rateLimitKickTrigger.tryAcquire(null);
			}
	);
	
	public final SupplierBasedConcurrencyLimiter<FullDataSourceRequestMessage> postRelogUpdateRequestConcurrencyLimiter = new SupplierBasedConcurrencyLimiter<>(
			() -> ServerNetworking.postRelogUpdateConcurrencyLimit.get(),
			msg -> {
				msg.sendResponse(new RateLimitedException("Max concurrent post-relog update requests: " + this.config.getPostRelogUpdateConcurrencyLimit()));
				this.rateLimitKickTrigger.tryAcquire(null);
			}
	);
	
	
	
	public ServerPlayerState(IServerPlayerWrapper serverPlayer) { this.serverPlayer = serverPlayer; }
    
}

