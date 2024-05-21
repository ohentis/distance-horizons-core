package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfig;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfigChangeListener;
import com.seibel.distanthorizons.core.network.messages.plugin.session.RemotePlayerConfigMessage;
import com.seibel.distanthorizons.core.network.messages.plugin.PluginCloseEvent;
import com.seibel.distanthorizons.core.network.messages.plugin.base.HelloMessage;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.messages.plugin.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.plugin.fullData.generation.GenTaskPriorityRequestMessage;
import com.seibel.distanthorizons.core.network.plugin.PluginChannelSession;
import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedRateAndConcurrencyLimiter;
import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedRateLimiter;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentHashMap;

import static com.seibel.distanthorizons.core.config.Config.Client.Advanced.Multiplayer.ServerNetworking;

public class ServerPlayerState
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	public final IServerPlayerWrapper serverPlayer;
	public final PluginChannelSession connection = new PluginChannelSession();
	private final MultiplayerConfigChangeListener configChangeListener = new MultiplayerConfigChangeListener(this::onConfigChanged);
	
	@NotNull
	public ConstrainedMultiplayerConfig config = new ConstrainedMultiplayerConfig();
	
	private final ConcurrentHashMap<DhServerLevel, RateLimiterSet> rateLimiterSets = new ConcurrentHashMap<>();
	public RateLimiterSet getRateLimiterSet(DhServerLevel level)
	{
		return this.rateLimiterSets.computeIfAbsent(level, ignored -> new RateLimiterSet());
	}
	public void clearRateLimiterSets()
	{
		this.rateLimiterSets.clear();
	}
	
	public ServerPlayerState(IServerPlayerWrapper serverPlayer)
	{
		this.serverPlayer = serverPlayer;
		
		this.connection.registerHandler(RemotePlayerConfigMessage.class, remotePlayerConfigMessage ->
		{
			this.config.clientConfig = (MultiplayerConfig) remotePlayerConfigMessage.payload;
			this.connection.sendMessage(new RemotePlayerConfigMessage(this.config));
		});
		
		this.connection.registerHandler(HelloMessage.class, msg -> {
			this.initializeLodSession();
		});
		
		this.connection.registerHandler(PluginCloseEvent.class, event -> {
			// Noop
		});
	}
	
	public void initializeLodSession()
	{
	}
	
	public void close()
	{
		this.configChangeListener.close();
		this.connection.close();
	}
	
	private void onConfigChanged()
	{
		this.connection.sendMessage(new RemotePlayerConfigMessage(this.config));
	}
	
	
	public class RateLimiterSet
	{
		public final SupplierBasedRateAndConcurrencyLimiter<FullDataSourceRequestMessage> fullDataRequestConcurrencyLimiter = new SupplierBasedRateAndConcurrencyLimiter<>(
				() -> ServerNetworking.generationRequestRCLimit.get(),
				msg -> {
					msg.sendResponse(new RateLimitedException("Full data request rate/concurrency limit: " + ServerPlayerState.this.config.getFullDataRequestConcurrencyLimit()));
				}
		);
		
		public final SupplierBasedRateLimiter<GenTaskPriorityRequestMessage> genTaskPriorityRequestRateLimiter = new SupplierBasedRateLimiter<>(
				() -> ServerNetworking.genTaskPriorityRequestRateLimit.get(),
				msg -> {
					msg.sendResponse(new RateLimitedException("Generation task priority check rate limit: " + ServerPlayerState.this.config.getFullDataRequestConcurrencyLimit()));
				}
		);
		
		public final SupplierBasedRateAndConcurrencyLimiter<FullDataSourceRequestMessage> loginDataSyncRCLimiter = new SupplierBasedRateAndConcurrencyLimiter<>(
				() -> ServerNetworking.loginDataSyncRCLimit.get(),
				msg -> {
					msg.sendResponse(new RateLimitedException("Data sync rate/concurrency limit: " + ServerPlayerState.this.config.getLoginDataSyncRCLimit()));
				}
		);
		
	}
	
}