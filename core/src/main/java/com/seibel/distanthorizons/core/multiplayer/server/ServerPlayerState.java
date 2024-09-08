package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.multiplayer.config.SessionConfig;
import com.seibel.distanthorizons.core.network.messages.base.CurrentLevelKeyMessage;
import com.seibel.distanthorizons.core.network.messages.base.SessionConfigMessage;
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
	public SessionConfig config = new SessionConfig();
	private final SessionConfig.ChangeListener configChangeListener = new SessionConfig.ChangeListener(this::onConfigChanged);

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
		
		this.session.registerHandler(SessionConfigMessage.class, sessionConfigMessage ->
		{
			this.config.constrainingConfig = sessionConfigMessage.config;
			this.sendLevelKey(null);
			this.session.sendMessage(new SessionConfigMessage(this.config));
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
		this.session.sendMessage(new SessionConfigMessage(this.config));
	}
	
	public void close()
	{
		this.levelKeyPrefixChangeListener.close();
		this.configChangeListener.close();
		this.session.close();
	}
	
	
	
	public class RateLimiterSet
	{
		public final SupplierBasedRateAndConcurrencyLimiter<FullDataSourceRequestMessage> generationRequestRateLimiter = new SupplierBasedRateAndConcurrencyLimiter<>(
				() -> ServerNetworking.generationRequestRateLimit.get(),
				msg -> {
					msg.sendResponse(new RateLimitedException("Full data request rate limit: " + ServerPlayerState.this.config.getGenerationRequestRateLimit()));
				}
		);
		
		public final SupplierBasedRateAndConcurrencyLimiter<FullDataSourceRequestMessage> syncOnLoginRateLimiter = new SupplierBasedRateAndConcurrencyLimiter<>(
				() -> ServerNetworking.syncOnLoginRateLimit.get(),
				msg -> {
					msg.sendResponse(new RateLimitedException("Sync on login rate limit: " + ServerPlayerState.this.config.getSyncOnLoginRateLimit()));
				}
		);
		
	}
	
}