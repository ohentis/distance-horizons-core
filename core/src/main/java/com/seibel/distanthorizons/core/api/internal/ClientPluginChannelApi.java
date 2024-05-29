package com.seibel.distanthorizons.core.api.internal;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IKeyedClientLevelManager;
import com.seibel.distanthorizons.core.level.IServerKeyedClientLevel;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.messages.plugin.PluginCloseEvent;
import com.seibel.distanthorizons.core.network.messages.plugin.CurrentLevelKeyMessage;
import com.seibel.distanthorizons.core.network.messages.plugin.base.ClientHelloMessage;
import com.seibel.distanthorizons.core.network.plugin.PluginChannelSession;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.LogManager;

import java.util.function.Consumer;

/** This class is used to manage the plugin channel session and Multiverse level keys. */
public class ClientPluginChannelApi
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IKeyedClientLevelManager KEYED_CLIENT_LEVEL_MANAGER = SingletonInjector.INSTANCE.get(IKeyedClientLevelManager.class);
	
	private final Consumer<IClientLevelWrapper> levelUnloadHandler;
	private final Consumer<IServerKeyedClientLevel> multiverseLevelLoadHandler;
	
	private PluginChannelSession session;
	
	
	public boolean allowLoadingLevel()
	{
		return (KEYED_CLIENT_LEVEL_MANAGER.isEnabled() && KEYED_CLIENT_LEVEL_MANAGER.getServerKeyedLevel() != null)
				|| !KEYED_CLIENT_LEVEL_MANAGER.isEnabled();
	}
	
	
	public ClientPluginChannelApi(Consumer<IServerKeyedClientLevel> levelLoadHandler, Consumer<IClientLevelWrapper> levelUnloadHandler)
	{
		this.levelUnloadHandler = levelUnloadHandler;
		this.multiverseLevelLoadHandler = levelLoadHandler;
		
	}
	
	public void onJoin(PluginChannelSession session)
	{
		this.session = session;
		
		this.session.sendMessage(new ClientHelloMessage());
		
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
				this.levelUnloadHandler.accept(clientLevel);
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
	
	public void handlePacket(ByteBuf buffer)
	{
		this.session.decodeAndHandle(buffer);
	}
	
}