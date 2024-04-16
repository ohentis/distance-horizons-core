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

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.messages.netty.base.NettyCloseEvent;
import com.seibel.distanthorizons.core.network.messages.netty.base.CloseReasonMessage;
import com.seibel.distanthorizons.core.network.messages.netty.base.HelloMessage;
import com.seibel.distanthorizons.core.network.protocol.netty.NettyMessageHandler;
import com.seibel.distanthorizons.core.network.protocol.netty.NettyChannelInitializer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.apache.logging.log4j.LogManager;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class NettyClient extends NettyEventSource implements INettyConnection, AutoCloseable
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
    private enum EConnectionState
	{
		INITIAL,
        OPEN,
		RECONNECTING,
		GOT_CLOSE_REASON,
        CLOSED
    }
	private static final Set<EConnectionState> closedStates = EnumSet.of(
			EConnectionState.GOT_CLOSE_REASON,
			EConnectionState.CLOSED
	);
	private final AtomicReference<EConnectionState> connectionState = new AtomicReference<>(EConnectionState.INITIAL);
	/** Indicates whether the client is working. */
	public boolean isInitialized() { return this.connectionState.get() != EConnectionState.INITIAL; }
	/** Indicates whether the client is closed(-ing) and should not be used. */
	public boolean isClosed() { return closedStates.contains(this.connectionState.get()); }
	
	private static final int RECONNECTION_DELAY_SEC = 5;
	public static final int RECONNECTION_ATTEMPTS = 3;
	
	private InetSocketAddress address;
	
	@Nullable
	private Throwable closeReason;
	@Override
	@Nullable
	public Throwable getCloseReason() { return this.closeReason; }
	
	private final EventLoopGroup workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("DH-Network - Client Thread"));
    private final Bootstrap clientBootstrap = new Bootstrap()
            .group(this.workerGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.SO_KEEPALIVE, true)
		    .handler(new NettyChannelInitializer(new NettyMessageHandler(
		            (ctx, msg) -> {
			            msg.setConnection(this);
						this.handleMessage(msg);
					},
		            ctx -> this.addNewConnection(this),
		            (ctx, closeReason) -> this.closeReason = closeReason
            )));
	
    private Channel channel;
	
	private final AtomicInteger reconnectionAttemptsLeft = new AtomicInteger(RECONNECTION_ATTEMPTS);
	/** Returns the amount of reconnections the client will attempt to perform before giving up. */
	public int getReconnectionAttemptsLeft() { return this.reconnectionAttemptsLeft.get(); }
	
	
	public NettyClient()
	{
		this.registerHandler(CloseReasonMessage.class, closeReasonMessage ->
		{
			String fullCloseText = "[Server] " + closeReasonMessage.reason;
			LOGGER.warn(fullCloseText);
			this.closeReason = new Exception(fullCloseText);
			this.connectionState.set(EConnectionState.GOT_CLOSE_REASON);
        });
		
		this.registerHandler(NettyCloseEvent.class, closeEvent ->
		{
            LOGGER.info("Disconnected from server: "+this.getRemoteAddress());
			if (this.connectionState.get() == EConnectionState.GOT_CLOSE_REASON)
			{
				this.close();
			}
        });
    }
	
	public void resetAndConnectTo(String host, int port)
	{
		if (this.connectionState.getAndUpdate(state -> state != EConnectionState.CLOSED ? EConnectionState.INITIAL : state) == EConnectionState.CLOSED)
		{
			return;
		}
		
		// Remove IPv6 brackets
		host = host.replaceAll("[\\[\\]]", "");
		this.address = new InetSocketAddress(host port);
		
		if (this.channel != null)
		{
			this.channel.close().syncUninterruptibly();
		}
		
		this.reconnectionAttemptsLeft.set(RECONNECTION_ATTEMPTS);
		this.connect();
	}
	
	public void connect()
	{
		if (!this.connectionState.compareAndSet(EConnectionState.INITIAL, EConnectionState.OPEN))
		{
			return;
		}
		
		LOGGER.info("Connecting to server: " + this.address);
        ChannelFuture connectFuture = this.clientBootstrap.connect(this.address);
		this.channel = connectFuture.channel();
		
        connectFuture.addListener((ChannelFuture channelFuture) -> 
		{
            if (!channelFuture.isSuccess())
			{
				LOGGER.info("Connection failed: " + channelFuture.cause());
				return;
			}
			
			this.sendMessage(new HelloMessage());
        });
		
		this.channel.closeFuture().addListener((ChannelFuture channelFuture) ->
		{
			this.completeAllFuturesExceptionally(channelFuture.cause() != null
					? channelFuture.cause()
					: new ChannelException("Channel is closed."));
			
			if (this.connectionState.get() == EConnectionState.OPEN)
			{
				int reconnectionAttemptsLeft = this.reconnectionAttemptsLeft.decrementAndGet();
				LOGGER.info("Reconnection attempts left: [" + this.reconnectionAttemptsLeft.get() + "] of [" + RECONNECTION_ATTEMPTS + "].");
				
				if (reconnectionAttemptsLeft != 0)
				{
					if (this.connectionState.compareAndSet(EConnectionState.OPEN, EConnectionState.RECONNECTING))
					{
						this.workerGroup.schedule(() ->
						{
							if (this.connectionState.compareAndSet(EConnectionState.RECONNECTING, EConnectionState.INITIAL))
							{
								this.connect();
							}
						}, RECONNECTION_DELAY_SEC, TimeUnit.SECONDS);
					}
				}
				else
				{
					this.connectionState.compareAndSet(EConnectionState.OPEN, EConnectionState.GOT_CLOSE_REASON);
				}
			}
		});
    }
	
	@Override
	public ChannelHandlerContext getChannelContext()
	{
		if (this.channel == null)
		{
			return null;
		}
		return this.channel.pipeline().context(NettyMessageHandler.class);
	}
	
	@Override
	public NettyEventSource getRequestHandler()
	{
		return this;
	}
	
	@Override
    public void close() 
	{
		EConnectionState stateChangeResult = this.connectionState.getAndSet(EConnectionState.CLOSED);
		if (stateChangeResult == EConnectionState.CLOSED || stateChangeResult == EConnectionState.INITIAL)
		{
			return;
		}
		
		this.channel.close().syncUninterruptibly();
		this.workerGroup.shutdownGracefully();
		
		super.close();
    }
	
}
