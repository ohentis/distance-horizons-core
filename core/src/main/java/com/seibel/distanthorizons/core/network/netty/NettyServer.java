/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2023 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.network.netty;

import com.google.common.collect.MapMaker;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.messages.netty.base.NettyCloseEvent;
import com.seibel.distanthorizons.core.network.messages.netty.base.HelloMessage;
import com.seibel.distanthorizons.core.network.protocol.netty.NettyMessageHandler;
import com.seibel.distanthorizons.core.network.protocol.netty.NettyChannelInitializer;
import com.seibel.distanthorizons.coreapi.ModInfo;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.apache.logging.log4j.LogManager;

import javax.annotation.Nullable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class NettyServer extends NettyEventSource implements AutoCloseable
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	public final int port;
	
	private final EventLoopGroup bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("DH-Network - Server Boss Thread"));
	private final EventLoopGroup workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("DH-Network - Server Worker Thread"));
	private final AtomicBoolean isClosed = new AtomicBoolean();
	
	private final ConcurrentMap<ChannelHandlerContext, INettyConnection> connections = new MapMaker().weakKeys().weakValues().makeMap();
	
	
	
	public NettyServer(int port)
	{
		this.port = port;
		
		LOGGER.info("Starting server on port "+port);
		this.registerHandlers();
		this.bind();
	}
	
	private void registerHandlers()
	{
		this.registerHandler(HelloMessage.class, helloMessage ->
		{
			INettyConnection connection = helloMessage.getConnection();
			LOGGER.info("Client connected: "+connection.getRemoteAddress());

			if (helloMessage.version != ModInfo.PROTOCOL_VERSION)
			{
				try
				{
					String disconnectReason = "Version mismatch. Server version: ["+ModInfo.PROTOCOL_VERSION+"], client version: ["+helloMessage.version+"].";
					LOGGER.info("Disconnecting the client ["+connection.getRemoteAddress()+"]: "+disconnectReason);
					connection.disconnect(disconnectReason);
				}
				catch (Exception e)
				{
					throw new RuntimeException(e);
				}
				return;
			}

			connection.sendMessage(new HelloMessage());
		});
		
		this.registerHandler(NettyCloseEvent.class, closeEvent ->
		{
			INettyConnection connection = closeEvent.getConnection();
			LOGGER.info("Client disconnected: "+connection.getRemoteAddress());
			this.completeAllFuturesExceptionally(closeEvent.getConnection(), connection.getCloseReason());
		});
	}
	
	private void bind()
	{
		ServerBootstrap bootstrap = new ServerBootstrap()
				.group(this.bossGroup, this.workerGroup)
				.channel(NioServerSocketChannel.class)
				.handler(new LoggingHandler(LogLevel.DEBUG))
				.childHandler(new NettyChannelInitializer(new NettyMessageHandler(
						(ctx, msg) -> {
							msg.setConnection(this.connections.computeIfAbsent(ctx, Connection::new));
							this.handleMessage(msg);
						},
						ctx -> this.addNewConnection(this.connections.computeIfAbsent(ctx, Connection::new)),
						(ctx, closeReason) -> ((Connection) this.connections.computeIfAbsent(ctx, Connection::new)).closeReason = closeReason
				)));
		
		ChannelFuture bindFuture = bootstrap.bind(this.port);
		bindFuture.addListener((ChannelFuture channelFuture) -> 
		{
			if (!channelFuture.isSuccess())
			{
				throw new RuntimeException("Failed to bind: " + channelFuture.cause());
			}
			
			LOGGER.info("Server is started on port "+this.port);
		});
		
		Channel channel = bindFuture.channel();
		channel.closeFuture().addListener(future -> this.close());
	}
	
	@Override
	public void close()
	{
		if (!this.isClosed.compareAndSet(false, true))
		{
			return;
		}
		
		LOGGER.info("Shutting down the network server.");
		this.workerGroup.shutdownGracefully().syncUninterruptibly();
		this.bossGroup.shutdownGracefully().syncUninterruptibly();
		LOGGER.info("Network server has been closed.");
		
		super.close();
	}
	
	public class Connection implements INettyConnection
	{
		private final ChannelHandlerContext channelContext;
		
		@Nullable
		private Throwable closeReason;
		@Override
		@Nullable
		public Throwable getCloseReason() { return this.closeReason; }
		
		public Connection(ChannelHandlerContext channelContext)
		{
			this.channelContext = channelContext;
		}
		
		@Override
		public ChannelHandlerContext getChannelContext()
		{
			return this.channelContext;
		}
		
		@Override
		public NettyEventSource getRequestHandler()
		{
			return NettyServer.this;
		}
		
	}
}
