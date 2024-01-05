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

package com.seibel.distanthorizons.core.network;

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.messages.base.CloseEvent;
import com.seibel.distanthorizons.core.network.messages.base.CloseReasonMessage;
import com.seibel.distanthorizons.core.network.messages.base.HelloMessage;
import com.seibel.distanthorizons.core.network.protocol.MessageHandler;
import com.seibel.distanthorizons.core.network.protocol.NetworkChannelInitializer;
import com.seibel.distanthorizons.core.network.protocol.NetworkMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.apache.logging.log4j.Logger;

import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class NetworkClient extends NetworkEventSource implements IConnection, AutoCloseable
{
    private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
    private enum EConnectionState
	{
		INITIAL,
        OPEN,
        RECONNECT,
		CLOSING,
        CLOSED
    }
	private static final Set<EConnectionState> closedStates = EnumSet.of(
			EConnectionState.CLOSING,
			EConnectionState.CLOSED
	);
	
    private static final int FAILURE_RECONNECT_DELAY_SEC = 5;
    private static final int FAILURE_RECONNECT_ATTEMPTS = 3;
	
    // TODO move to payload of some sort
    private final InetSocketAddress address;
	
	/** Indicates whether the client is initialized and not started connecting yet. */
	public boolean isInitialState() { return this.connectionState == EConnectionState.INITIAL; }
	/** Indicates whether the client is closed(-ing) and should not be used. */
	public boolean isClosed() { return closedStates.contains(this.connectionState); }
	private boolean ready;
	/** Indicates whether the connection is established and first message is sent. */
	public boolean isReady() { return ready; }
	
    private final EventLoopGroup workerGroup = new NioEventLoopGroup(new DefaultThreadFactory("DH-Network - Client Thread"));
    private final Bootstrap clientBootstrap = new Bootstrap()
            .group(this.workerGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(new NetworkChannelInitializer(new MessageHandler(
		            (ctx, msg) -> {
			            msg.setConnection(this);
						this.handleMessage(msg);
					},
		            ctx -> this.addNewConnection(this)
            )));
	
    private EConnectionState connectionState = EConnectionState.INITIAL;
    private Channel channel;
    private int reconnectAttempts = FAILURE_RECONNECT_ATTEMPTS;
	
	
	
    public NetworkClient(String host, int port)
	{
        this.address = new InetSocketAddress(host, port);
		this.registerHandlers();
    }
	
    private void registerHandlers() 
	{
		this.registerHandler(CloseReasonMessage.class, closeReasonMessage ->
		{
            LOGGER.info(closeReasonMessage.reason);
			this.connectionState = EConnectionState.CLOSING;
        });
		
		this.registerHandler(CloseEvent.class, closeEvent ->
		{
            LOGGER.info("Disconnected from server: "+this.getRemoteAddress());
            if (this.connectionState == EConnectionState.CLOSING)
			{
				this.close();
			}
        });
    }
	
	public void startConnecting()
	{
		if (!isInitialState()) return;
		this.connect();
	}

    private void connect() 
	{
        LOGGER.info("Connecting to server: "+this.address);
		this.connectionState = EConnectionState.OPEN;

		// FIXME sometimes this causes the MC connection to crash 
		//  this might happen if the URL can't be converted to a IP (IE UnknownHostException)
        ChannelFuture connectFuture = this.clientBootstrap.connect(this.address);
		this.channel = connectFuture.channel();
		
        connectFuture.addListener((ChannelFuture channelFuture) -> 
		{
            if (!channelFuture.isSuccess())
			{
				LOGGER.warn("Connection failed: "+channelFuture.cause());
				return;
			}
			
			sendMessage(new HelloMessage());
			ready = true;
        });
		
		this.channel.closeFuture().addListener((ChannelFuture channelFuture) ->
		{
			ready = false;
			this.completeAllFuturesExceptionally(channelFuture.cause() != null
					? channelFuture.cause()
					: new ChannelException("Channel is closed."));
			
			if (this.connectionState != EConnectionState.OPEN)
				return;
			
			this.reconnectAttempts--;
			LOGGER.info("Reconnection attempts left: [" + this.reconnectAttempts + "] of [" + FAILURE_RECONNECT_ATTEMPTS + "].");
			if (this.reconnectAttempts == 0)
			{
				this.connectionState = EConnectionState.CLOSING;
				return;
			}
			
			this.connectionState = EConnectionState.RECONNECT;
			this.workerGroup.schedule(this::connect, FAILURE_RECONNECT_DELAY_SEC, TimeUnit.SECONDS);
		});
    }
	
	@Override
	public ChannelHandlerContext getChannelContext()
	{
		return this.channel.pipeline().context(MessageHandler.class);
	}
	
	@Override
	public NetworkEventSource getRequestHandler()
	{
		return this;
	}
	
	@Override
    public void close() 
	{
        if (this.connectionState == EConnectionState.CLOSED)
		{
			return;
		}
		
		this.connectionState = EConnectionState.CLOSED;
		this.channel.close().syncUninterruptibly();
		this.workerGroup.shutdownGracefully();
		
		super.close();
    }
	
}
