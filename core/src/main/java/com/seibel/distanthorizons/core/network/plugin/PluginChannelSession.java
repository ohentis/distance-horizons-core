package com.seibel.distanthorizons.core.network.plugin;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.NetworkEventSource;
import com.seibel.distanthorizons.core.network.messages.plugin.PluginCloseEvent;
import com.seibel.distanthorizons.core.network.messages.plugin.PluginMessageRegistry;
import com.seibel.distanthorizons.core.network.protocol.plugin.PluginMessageEncoder;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.coreapi.ModInfo;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class PluginChannelSession extends NetworkEventSource
{
	/**
	 * 4 MiB should be enough for any transferred data. <br>
	 * Currently largest transferred data is DH full data sections, which usually don't exceed 1-2 MiB in size.
	 */
	private static final int MAX_MESSAGE_LENGTH = 4194304;
	
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	private final IPluginPacketSender packetSender = SingletonInjector.INSTANCE.get(IPluginPacketSender.class);
	
	/**
	 * When set to true, any received data will be ignored. <br>
	 * This does not include wrong versions, which are ignored without setting this flag,
	 * to allow multi-compat servers.
	 */
	private final AtomicBoolean isClosed = new AtomicBoolean();
	@Nullable
	private IServerPlayerWrapper serverPlayer;
	
	
	public PluginChannelSession()
	{
		super(PluginMessageRegistry.INSTANCE);
	}
	public PluginChannelSession(@Nullable IServerPlayerWrapper serverPlayer)
	{
		super(PluginMessageRegistry.INSTANCE);
		this.serverPlayer = serverPlayer;
	}
	
	
	public void decodeAndHandle(ByteBuf byteBuf)
	{
		if (this.isClosed.get())
		{
			return;
		}
		
		try
		{
			int version = byteBuf.readShort();
			if (version != ModInfo.PROTOCOL_VERSION)
			{
				return;
			}
			
			PluginChannelMessage msg = PluginMessageRegistry.INSTANCE.createMessage(byteBuf.readUnsignedShort());
			msg.decode(byteBuf);
			msg.serverPlayer = this.serverPlayer;
			
			this.handleMessage(msg);
		}
		catch (Throwable e)
		{
			LOGGER.error("Failed to handle the message. New messages will be ignored.", e);
			LOGGER.error("Buffer: " + byteBuf.toString());
			byteBuf.resetReaderIndex();
			LOGGER.error("Buffer contents: " + ByteBufUtil.hexDump(byteBuf));
			this.close();
		}
	}
	
	<TResponse extends TrackableMessage> CompletableFuture<TResponse> sendRequest(TrackableMessage msg, Class<TResponse> responseClass)
	{
		CompletableFuture<TResponse> responseFuture = this.createRequest(this, msg, responseClass);
		this.sendMessage(msg);
		return responseFuture;
	}
	
	public void sendMessage(PluginChannelMessage message)
	{
		LOGGER.debug("Sending message: " + message);
		
		Consumer<ByteBuf> encoder = buffer -> {
			buffer.writeShort(ModInfo.PROTOCOL_VERSION);
			buffer.writeShort(PluginMessageRegistry.INSTANCE.getMessageId(message));
			message.encode(buffer);
		};
		
		if (this.serverPlayer != null)
		{
			this.packetSender.sendPluginPacketServer(this.serverPlayer, encoder);
		}
		else
		{
			this.packetSender.sendPluginPacketClient(encoder);
		}
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