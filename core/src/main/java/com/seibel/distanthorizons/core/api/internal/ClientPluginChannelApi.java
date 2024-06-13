package com.seibel.distanthorizons.core.api.internal;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IKeyedClientLevelManager;
import com.seibel.distanthorizons.core.level.IServerKeyedClientLevel;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.messages.plugin.PluginCloseEvent;
import com.seibel.distanthorizons.core.network.messages.plugin.CurrentLevelKeyMessage;
import com.seibel.distanthorizons.core.network.plugin.PluginChannelSession;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.apache.logging.log4j.LogManager;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * This class is used to manage the level keys.
 */
public class ClientPluginChannelApi
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IKeyedClientLevelManager KEYED_CLIENT_LEVEL_MANAGER = SingletonInjector.INSTANCE.get(IKeyedClientLevelManager.class);
	
	private final Consumer<IClientLevelWrapper> levelUnloadHandler;
	private final Consumer<IServerKeyedClientLevel> multiverseLevelLoadHandler;
	
	public PluginChannelSession session;
	
	
	public boolean allowLevelAutoload()
	{
		return (KEYED_CLIENT_LEVEL_MANAGER.isEnabled() && KEYED_CLIENT_LEVEL_MANAGER.getServerKeyedLevel() != null)
				|| !KEYED_CLIENT_LEVEL_MANAGER.isEnabled();
	}
	
	
	public ClientPluginChannelApi(Consumer<IServerKeyedClientLevel> levelLoadHandler, Consumer<IClientLevelWrapper> levelUnloadHandler)
	{
		this.levelUnloadHandler = levelUnloadHandler;
		this.multiverseLevelLoadHandler = levelLoadHandler;
	}
	
	public void onJoin(@NonNull PluginChannelSession session)
	{
		Objects.requireNonNull(session);
		this.session = session;
		this.session.registerHandler(CurrentLevelKeyMessage.class, this::onCurrentLevelKeyMessage);
		this.session.registerHandler(PluginCloseEvent.class, this::onClose);
	}
	
	private void onCurrentLevelKeyMessage(CurrentLevelKeyMessage msg)
	{
		if (!msg.levelKey.matches("[a-zA-Z0-9_]+"))
		{
			throw new IllegalArgumentException("Server sent invalid world key name.");
		}
		
		LOGGER.info("Server level change event received, changing the level to [" + msg.levelKey + "].");
		
		MC.executeOnRenderThread(() -> {
			IClientLevelWrapper clientLevel = MC.getWrappedClientLevel(true);
			
			if (clientLevel != null)
			{
				// In either case only one of them will have an effect.
				this.levelUnloadHandler.accept(clientLevel);
				this.levelUnloadHandler.accept(KEYED_CLIENT_LEVEL_MANAGER.getServerKeyedLevel());
			}
			
			IServerKeyedClientLevel keyedLevel = KEYED_CLIENT_LEVEL_MANAGER.setServerKeyedLevel(clientLevel, msg.levelKey);
			this.multiverseLevelLoadHandler.accept(keyedLevel);
		});
	}
	
	public void onClientLevelUnload()
	{
		KEYED_CLIENT_LEVEL_MANAGER.clearServerKeyedLevel();
	}
	
	private void onClose(PluginCloseEvent event)
	{
		KEYED_CLIENT_LEVEL_MANAGER.disable();
	}
	
}