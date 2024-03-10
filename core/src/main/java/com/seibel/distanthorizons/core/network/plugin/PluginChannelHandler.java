package com.seibel.distanthorizons.core.network.plugin;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.NetworkEventSource;
import com.seibel.distanthorizons.core.network.messages.plugin.PluginCloseEvent;
import com.seibel.distanthorizons.core.network.messages.plugin.PluginMessageRegistry;
import com.seibel.distanthorizons.core.network.protocol.plugin.PluginMessageDecoder;
import com.seibel.distanthorizons.core.network.protocol.plugin.PluginMessageEncoder;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class PluginChannelHandler extends NetworkEventSource<PluginChannelMessage>
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	private final PluginMessageDecoder messageDecoder = new PluginMessageDecoder();
	private final PluginMessageEncoder messageEncoder = new PluginMessageEncoder();
	private final IPluginPacketSender packetSender = SingletonInjector.INSTANCE.get(IPluginPacketSender.class);
	
	/**
	 * When set to true, any received data will be ignored. <br>
	 * This does not include wrong versions, which are ignored without setting this flag,
	 * to allow multi-compat servers.
	 */
	private final AtomicBoolean isClosed = new AtomicBoolean();
	
	
	public PluginChannelHandler()
	{
		super(PluginMessageRegistry.INSTANCE);
	}
	
	
	public void decodeAndHandle(ByteBuf byteBuf)
	{
		if (this.isClosed.get())
		{
			return;
		}
		
		try
		{
			ArrayList<Object> messages = new ArrayList<>();
			this.messageDecoder.decode(byteBuf, messages);
			
			for (Object msg : messages)
			{
				this.handleMessage((PluginChannelMessage) msg);
			}
		}
		catch (Throwable e)
		{
			LOGGER.error("Failed to handle the message. New messages will be ignored. \n" + e);
			this.close();
		}
	}
	
	public void sendMessage(PluginChannelMessage message)
	{
		this.sendMessage(null, message);
	}
	public void sendMessage(@Nullable IServerPlayerWrapper serverPlayer, PluginChannelMessage message)
	{
		ByteBuf buffer = PooledByteBufAllocator.DEFAULT.buffer();
		this.messageEncoder.encode(message, buffer);
		
		this.packetSender.sendPluginPacket(serverPlayer, buffer);
	}
	
	@Override
	public void close()
	{
		if (!this.isClosed.compareAndSet(false, true))
		{
			return;
		}
		
		try
		{
			this.handleMessage(new PluginCloseEvent());
		}
		catch (Throwable ignored)
		{
		}
		
		super.close();
	}
	
}
