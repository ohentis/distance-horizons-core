package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfig;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfigChangeListener;
import com.seibel.distanthorizons.core.network.messages.base.CurrentLevelKeyMessage;
import com.seibel.distanthorizons.core.network.messages.base.RemotePlayerConfigMessage;
import com.seibel.distanthorizons.core.network.event.internal.CloseEvent;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.session.Session;
import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedRateAndConcurrencyLimiter;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentHashMap;

import static com.seibel.distanthorizons.core.config.Config.Client.Advanced.Multiplayer.ServerNetworking;

public class ServerPlayerState
{
	public final Session session;
	public IServerPlayerWrapper serverPlayer() { return this.session.serverPlayer; }
	
	@NotNull
	public ConstrainedMultiplayerConfig config = new ConstrainedMultiplayerConfig();
	private final MultiplayerConfigChangeListener configChangeListener = new MultiplayerConfigChangeListener(this::onConfigChanged);

	private String lastLevelKey = "";
	private final ConfigChangeListener<String> levelKeyPrefixChangeListener = new ConfigChangeListener<>(ServerNetworking.levelKeyPrefix, this::sendLevelKey);

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
		this.session = new Session(serverPlayer);
		
		this.session.registerHandler(RemotePlayerConfigMessage.class, remotePlayerConfigMessage ->
		{
			this.config.clientConfig = (MultiplayerConfig) remotePlayerConfigMessage.payload;
			this.sendLevelKey(null);
			this.session.sendMessage(new RemotePlayerConfigMessage(this.config));
		});
		
		this.session.registerHandler(CloseEvent.class, event -> {
			// No-op. removes "Unhandled message" log entries
		});
	}
	
	
	private void sendLevelKey(String ignored)
	{
		if (ServerNetworking.sendLevelKeys.get())
		{
			String levelKey = this.serverPlayer().getLevel().getKeyedLevelDimensionName();
			if (!levelKey.equals(this.lastLevelKey))
			{
				this.lastLevelKey = levelKey;
				this.session.sendMessage(new CurrentLevelKeyMessage(levelKey));
			}
		}
	}
	
	private void onConfigChanged()
	{
		this.session.sendMessage(new RemotePlayerConfigMessage(this.config));
	}
	
	public void close()
	{
		this.levelKeyPrefixChangeListener.close();
		this.configChangeListener.close();
		this.session.close();
	}
	
	
	
	public class RateLimiterSet
	{
		public final SupplierBasedRateAndConcurrencyLimiter<FullDataSourceRequestMessage> fullDataRequestConcurrencyLimiter = new SupplierBasedRateAndConcurrencyLimiter<>(
				() -> ServerNetworking.generationRequestRCLimit.get(),
				msg -> {
					msg.sendResponse(new RateLimitedException("Full data request rate/concurrency limit: " + ServerPlayerState.this.config.getFullDataRequestConcurrencyLimit()));
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