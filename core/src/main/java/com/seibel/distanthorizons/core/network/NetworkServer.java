package com.seibel.distanthorizons.core.network;

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.messages.AckMessage;
import com.seibel.distanthorizons.core.network.messages.CloseReasonMessage;
import com.seibel.distanthorizons.core.network.messages.CloseMessage;
import com.seibel.distanthorizons.core.network.messages.HelloMessage;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.MessageHandler;
import com.seibel.distanthorizons.core.network.protocol.NetworkChannelInitializer;
import com.seibel.distanthorizons.coreapi.ModInfo;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CompletableFuture;

public class NetworkServer extends NetworkEventSource implements AutoCloseable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	// TODO move to the config
	private final int port;
	
	private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
	private final EventLoopGroup workerGroup = new NioEventLoopGroup();
	private Channel channel;
	private boolean isClosed = false;
	
	
	
	public NetworkServer(int port)
	{
		this.port = port;
		
		LOGGER.info("Starting server on port "+port);
		this.registerHandlers();
		this.bind();
	}
	
	private void registerHandlers()
	{
		this.registerHandler(HelloMessage.class, (helloMessage, channelContext) -> 
		{
			LOGGER.info("Client connected: "+channelContext.channel().remoteAddress());

			if (helloMessage.version != ModInfo.PROTOCOL_VERSION)
			{
				try
				{
					String disconnectReason = "Version mismatch. Server version: ["+ModInfo.PROTOCOL_VERSION+"], client version: ["+helloMessage.version+"].";
					LOGGER.info("Disconnecting the client ["+channelContext.name()+"]: "+disconnectReason);
					this.disconnectClient(channelContext, disconnectReason);
				}
				catch (Exception e)
				{
					throw new RuntimeException(e);
				}
				return;
			}

			channelContext.writeAndFlush(new AckMessage(HelloMessage.class));
		});
		
		this.registerHandler(CloseMessage.class, (closeMessage, channelContext) -> 
		{
			LOGGER.info("Client disconnected: "+channelContext.channel().remoteAddress());
			this.completeAllFuturesExceptionally(channelContext, channelContext.channel().closeFuture().cause());
		});
	}
	
	private void bind()
	{
		ServerBootstrap bootstrap = new ServerBootstrap()
				.group(this.bossGroup, this.workerGroup)
				.channel(NioServerSocketChannel.class)
				.handler(new LoggingHandler(LogLevel.DEBUG))
				.childHandler(new NetworkChannelInitializer(new MessageHandler(this::handleMessage)));
		
		ChannelFuture bindFuture = bootstrap.bind(this.port);
		bindFuture.addListener((ChannelFuture channelFuture) -> 
		{
			if (!channelFuture.isSuccess())
			{
				throw new RuntimeException("Failed to bind: " + channelFuture.cause());
			}
			
			LOGGER.info("Server is started on port "+this.port);
		});
		
		this.channel = bindFuture.channel();
		this.channel.closeFuture().addListener(future -> this.close());
	}
	
	public void disconnectClient(ChannelHandlerContext ctx, String reason)
	{
		ctx.channel().config().setAutoRead(false);
		ctx.writeAndFlush(new CloseReasonMessage(reason))
				.addListener(ChannelFutureListener.CLOSE);
	}
	
	@Override
	public <TResponse extends FutureTrackableNetworkMessage> CompletableFuture<TResponse> sendRequest(ChannelHandlerContext ctx, FutureTrackableNetworkMessage msg)
	{
		return super.sendRequest(ctx, msg);
	}
	
	@Override
	public void close()
	{
		if (this.isClosed)
		{
			return;
		}
		this.isClosed = true;
		
		LOGGER.info("Shutting down the network server.");
		this.workerGroup.shutdownGracefully().syncUninterruptibly();
		this.bossGroup.shutdownGracefully().syncUninterruptibly();
		LOGGER.info("Network server has been closed.");
		
		super.close();
	}
	
}
