package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.messages.plugin.PluginCloseEvent;
import com.seibel.distanthorizons.core.network.messages.plugin.PluginHelloMessage;
import com.seibel.distanthorizons.core.network.messages.plugin.ServerConnectInfoMessage;
import com.seibel.distanthorizons.core.network.netty.INettyConnection;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.messages.netty.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.netty.fullData.generation.GenTaskPriorityRequestMessage;
import com.seibel.distanthorizons.core.network.plugin.PluginChannelHandler;
import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedRateAndConcurrencyLimiter;
import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedRateLimiter;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

import static com.seibel.distanthorizons.core.config.Config.Client.Advanced.Multiplayer.ServerNetworking;

public class ServerPlayerState
{
	private static final boolean DEBUG_ENABLE_OVERRIDES_IN_LAN = false;
	
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	public final IServerPlayerWrapper serverPlayer;
	public INettyConnection connection;
	
	private final int serverPort;
	public final PluginChannelHandler pluginChannelHandler = new PluginChannelHandler();
	
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
	
	public ServerPlayerState(IServerPlayerWrapper serverPlayer, int serverPort)
	{
		this.serverPlayer = serverPlayer;
		this.serverPort = serverPort;
		
		this.pluginChannelHandler.registerHandler(PluginHelloMessage.class, msg -> {
			this.sendConnectInfo();
		});
		
		this.pluginChannelHandler.registerHandler(PluginCloseEvent.class, event -> {
			// Noop
		});
	}
	
	public void sendConnectInfo()
	{
		String ipOverride = ServerNetworking.connectIpOverride.get();
		int portOverride = ServerNetworking.connectPortOverride.get();
		
		// IP/port overrides are intended for using with port forwarding services,
		// and LAN clients are unlikely to need to hop through internet
		InetAddress ip = ((InetSocketAddress) this.serverPlayer.getRemoteAddress()).getAddress();
		boolean isLanPlayer = !DEBUG_ENABLE_OVERRIDES_IN_LAN && (ip.isLinkLocalAddress() || ip.isSiteLocalAddress());
		
		this.pluginChannelHandler.sendMessageServer(this.serverPlayer, new ServerConnectInfoMessage(
				!isLanPlayer && !ipOverride.isEmpty() ? ipOverride : null,
				!isLanPlayer && portOverride != 0 ? portOverride : this.serverPort
		));
	}
	
	public void close()
	{
		this.pluginChannelHandler.close();
	}
	
	
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

