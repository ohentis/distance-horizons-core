package com.seibel.distanthorizons.core.network.plugin;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.NetworkEventSource;
import com.seibel.distanthorizons.core.network.messages.PluginMessageRegistry;
import com.seibel.distanthorizons.core.network.messages.plugin.PluginCloseEvent;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.coreapi.ModInfo;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class PluginChannelSession extends NetworkEventSource
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	private static final IPluginPacketSender PACKET_SENDER = SingletonInjector.INSTANCE.get(IPluginPacketSender.class);
	
	/**
	 * When non-null, any received data will be ignored. <br>
	 * This does not include wrong versions, which are ignored without setting this flag,
	 * to allow multi-compat servers.
	 */
	private final AtomicReference<Throwable> closeReason = new AtomicReference<>();
	public Throwable getCloseReason() { return this.closeReason.get(); }
	public boolean isClosed() { return this.closeReason.get() != null; }
	
	@Nullable
	public final IServerPlayerWrapper serverPlayer;
	
	public PluginChannelSession(@Nullable IServerPlayerWrapper serverPlayer)
	{
		this.serverPlayer = serverPlayer;
	}
	
	
	public void decodeAndHandle(ByteBuf byteBuf)
	{
		if (this.closeReason.get() != null)
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
			msg.session = this;
			
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
	
	public <TResponse extends TrackableMessage> CompletableFuture<TResponse> sendRequest(TrackableMessage msg, Class<TResponse> responseClass)
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
			PACKET_SENDER.sendPluginPacketServer(this.serverPlayer, encoder);
		}
		else
		{
			PACKET_SENDER.sendPluginPacketClient(encoder);
		}
	}
	
	public void close(Throwable closeReason)
	{
		if (!this.closeReason.compareAndSet(null, closeReason))
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