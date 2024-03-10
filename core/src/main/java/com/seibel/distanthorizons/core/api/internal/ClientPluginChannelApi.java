package com.seibel.distanthorizons.core.api.internal;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IKeyedClientLevelManager;
import com.seibel.distanthorizons.core.level.IServerKeyedClientLevel;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.messages.plugin.PluginCloseEvent;
import com.seibel.distanthorizons.core.network.messages.plugin.CurrentLevelKeyMessage;
import com.seibel.distanthorizons.core.network.messages.plugin.PluginHelloMessage;
import com.seibel.distanthorizons.core.network.messages.plugin.ServerConnectInfoMessage;
import com.seibel.distanthorizons.core.network.plugin.PluginChannelHandler;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.LogManager;

import java.util.function.Consumer;

public class ClientPluginChannelApi implements AutoCloseable
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IKeyedClientLevelManager KEYED_CLIENT_LEVEL_MANAGER = SingletonInjector.INSTANCE.get(IKeyedClientLevelManager.class);
	
	private final PluginChannelHandler channelHandler = new PluginChannelHandler();
	
	private final Consumer<IClientLevelWrapper> levelUnloadHandler;
	private final Consumer<IServerKeyedClientLevel> multiverseLevelLoadHandler;
	
	
	public boolean allowLoadingLevel()
	{
		return (KEYED_CLIENT_LEVEL_MANAGER.isEnabled() && KEYED_CLIENT_LEVEL_MANAGER.getServerKeyedLevel() != null)
				|| !KEYED_CLIENT_LEVEL_MANAGER.isEnabled();
	}
	
	
	public ClientPluginChannelApi(Consumer<IServerKeyedClientLevel> levelLoadHandler, Consumer<IClientLevelWrapper> levelUnloadHandler)
	{
		this.levelUnloadHandler = levelUnloadHandler;
		this.multiverseLevelLoadHandler = levelLoadHandler;
		
		this.channelHandler.registerHandler(CurrentLevelKeyMessage.class, this::onCurrentLevelKeyMessage);
		this.channelHandler.registerHandler(ServerConnectInfoMessage.class, this::onServerConnectInfoMessage);
		this.channelHandler.registerHandler(PluginCloseEvent.class, this::onClose);
	}
	
	public void onJoin()
	{
		this.channelHandler.sendMessage(new PluginHelloMessage());
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
	
	private void onServerConnectInfoMessage(ServerConnectInfoMessage msg)
	{
		// TODO
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
		this.channelHandler.decodeAndHandle(buffer);
	}
	
	@Override
	public void close()
	{
		this.channelHandler.close();
	}
	
}
