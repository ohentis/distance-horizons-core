package com.seibel.distanthorizons.core.network.plugin;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.messages.plugin.base.CloseReasonMessage;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;

import java.util.concurrent.CompletableFuture;

public class PluginChannelSessionAAAAA
{
	private static final IPluginPacketSender packetSender = SingletonInjector.INSTANCE.get(IPluginPacketSender.class);
	
	public final IServerPlayerWrapper serverPlayer;
	public boolean isClosed = false;
	
	public PluginChannelSessionAAAAA(IServerPlayerWrapper serverPlayer)
	{
		this.serverPlayer = serverPlayer;
	}
	
	ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	public void sendMessage(PluginChannelMessage message)
	{
		this.LOGGER.debug("Sending message: " + message);
		CompletableFuture<Void> future = new CompletableFuture<>();
		
		if (this.serverPlayer != null)
		{
			packetSender.sendPluginPacketServer(this.serverPlayer, message);
		}
		
		
		return future;
	}
	
	default <TResponse extends TrackableNettyMessage> CompletableFuture<TResponse> sendRequest(TrackableNettyMessage msg, Class<TResponse> responseClass)
	{
		CompletableFuture<TResponse> responseFuture = this.getRequestHandler().createRequest(this, msg, responseClass);
		this.sendMessage(msg).whenComplete((ignored, throwable) ->
		{
			if (throwable != null)
			{
				responseFuture.completeExceptionally(throwable);
			}
		});
		return responseFuture;
	}
	
	default void disconnect(String reason)
	{
		ChannelHandlerContext ctx = this.getChannelContext();
		if (ctx == null)
		{
			return;
		}
		
		ctx.channel().config().setAutoRead(false);
		ctx.writeAndFlush(new CloseReasonMessage(reason))
				.addListener(ChannelFutureListener.CLOSE);
	}
	
}