package com.seibel.distanthorizons.core.api.internal;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IKeyedClientLevelManager;
import com.seibel.distanthorizons.core.level.IServerKeyedClientLevel;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.event.CloseEvent;
import com.seibel.distanthorizons.core.network.messages.base.CurrentLevelKeyMessage;
import com.seibel.distanthorizons.core.network.session.Session;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.apache.logging.log4j.LogManager;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jetbrains.annotations.Nullable;

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
	
	private final Consumer<IServerKeyedClientLevel> levelLoadHandler;
	private final Consumer<IClientLevelWrapper> levelUnloadHandler;
	
	@Nullable
	public Session session;
	
	
	public boolean allowLevelLoading(IClientLevelWrapper level)
	{
		return (KEYED_CLIENT_LEVEL_MANAGER.isEnabled() && level instanceof IServerKeyedClientLevel)
				|| !KEYED_CLIENT_LEVEL_MANAGER.isEnabled();
	}
	
	
	public ClientPluginChannelApi(Consumer<IServerKeyedClientLevel> levelLoadHandler, Consumer<IClientLevelWrapper> levelUnloadHandler)
	{
		this.levelLoadHandler = levelLoadHandler;
		this.levelUnloadHandler = levelUnloadHandler;
	}
	
	public void onJoin(@NonNull Session session)
	{
		Objects.requireNonNull(session);
		this.session = session;
		session.registerHandler(CurrentLevelKeyMessage.class, this::onCurrentLevelKeyMessage);
		session.registerHandler(CloseEvent.class, this::onClose);
	}
	
	private void onCurrentLevelKeyMessage(CurrentLevelKeyMessage msg)
	{
		// prefix@namespace:path
		// 1-50 characters in total, all parts except namespace can be omitted
		if (!msg.levelKey.matches("^(?=.{1,50}$)([a-zA-Z0-9-_]+@)?[a-zA-Z0-9-_]+(:[a-zA-Z0-9-_]+)?$"))
		{
			throw new IllegalArgumentException("Server sent invalid level key.");
		}
		
		LOGGER.info("Server level key received: " + msg.levelKey);
		
		MC.executeOnRenderThread(() -> {
			IClientLevelWrapper clientLevel = MC.getWrappedClientLevel(true);
			IServerKeyedClientLevel existingKeyedClientLevel = KEYED_CLIENT_LEVEL_MANAGER.getServerKeyedLevel();

			if (existingKeyedClientLevel != null)
			{
				if (!existingKeyedClientLevel.getServerLevelKey().equals(msg.levelKey))
				{
					LOGGER.info("Unloading previous level with key: " + existingKeyedClientLevel.getServerLevelKey());
					this.levelUnloadHandler.accept(existingKeyedClientLevel);
				}
				else
				{
					LOGGER.info("Level key matches the previous level key, ignoring the message.");
				}
			}
			else
			{
				LOGGER.info("Unloading non-keyed level: " + clientLevel.getDimensionName());
				this.levelUnloadHandler.accept(clientLevel);
			}
			
			if (existingKeyedClientLevel == null || !existingKeyedClientLevel.getServerLevelKey().equals(msg.levelKey))
			{
				LOGGER.info("Loading level with key: " + msg.levelKey);
				IServerKeyedClientLevel keyedLevel = KEYED_CLIENT_LEVEL_MANAGER.setServerKeyedLevel(clientLevel, msg.levelKey);
				this.levelLoadHandler.accept(keyedLevel);
			}
		});
	}
	
	public void onClientLevelUnload()
	{
		KEYED_CLIENT_LEVEL_MANAGER.clearServerKeyedLevel();
	}
	
	private void onClose(CloseEvent event)
	{
		this.reset();
	}
	
	public void reset()
	{
		this.session = null;
		KEYED_CLIENT_LEVEL_MANAGER.disable();
	}
	
}