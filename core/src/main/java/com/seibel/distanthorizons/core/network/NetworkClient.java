package com.seibel.distanthorizons.core.network;

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.messages.CloseMessage;
import com.seibel.distanthorizons.core.network.messages.CloseReasonMessage;
import com.seibel.distanthorizons.core.network.messages.HelloMessage;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.network.protocol.MessageHandler;
import com.seibel.distanthorizons.core.network.protocol.NetworkChannelInitializer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.apache.logging.log4j.Logger;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class NetworkClient extends NetworkEventSource implements AutoCloseable 
{
    private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
    private enum EConnectionState
	{
        OPEN,
        RECONNECT,
        RECONNECT_FORCE,
        CLOSE_WAIT,
        CLOSED
    }
	
    private static final int FAILURE_RECONNECT_DELAY_SEC = 5;
    private static final int FAILURE_RECONNECT_ATTEMPTS = 5;
	
    // TODO move to payload of some sort
    private final InetSocketAddress address;
	
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final Bootstrap clientBootstrap = new Bootstrap()
            .group(this.workerGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(new NetworkChannelInitializer(new MessageHandler(this::handleMessage)));
	
    private EConnectionState connectionState;
    private Channel channel;
    private int reconnectAttempts = FAILURE_RECONNECT_ATTEMPTS;
	
	
	
    public NetworkClient(String host, int port)
	{
        this.address = new InetSocketAddress(host, port);
		
		this.registerHandlers();
		this.connect();
    }
	
    private void registerHandlers() 
	{
		this.registerAckHandler(HelloMessage.class, channelContext ->
		{
            LOGGER.info("Connected to server: "+channelContext.channel().remoteAddress());
        });
		
		this.registerHandler(CloseReasonMessage.class, (closeReasonMessage, channelContext) -> 
		{
            LOGGER.info(closeReasonMessage.reason);
			this.connectionState = EConnectionState.CLOSE_WAIT;
        });
		
		this.registerHandler(CloseMessage.class, (closeMessage, channelContext) ->
		{
            LOGGER.info("Disconnected from server: "+channelContext.channel().remoteAddress());
            if (this.connectionState == EConnectionState.CLOSE_WAIT)
			{
				this.close();
			}
        });
    }

    private void connect() 
	{
        LOGGER.info("Connecting to server: "+this.address);
		this.connectionState = EConnectionState.OPEN;

		// FIXME sometimes this causes the MC connection to crash 
		//  this might happen if the URL can't be converted to a IP (IE UnknownHostException)
        ChannelFuture connectFuture = this.clientBootstrap.connect(this.address);
        connectFuture.addListener((ChannelFuture channelFuture) -> 
		{
            if (!channelFuture.isSuccess()) 
			{
                LOGGER.warn("Connection failed: "+channelFuture.cause());
                return;
            }
			
			this.channel.writeAndFlush(new HelloMessage());
        });
		
		this.channel = connectFuture.channel();
		this.channel.closeFuture().addListener((ChannelFuture channelFuture) ->
		{
			this.completeAllFuturesExceptionally(channelFuture.cause());
			
			switch (this.connectionState)
			{
				case OPEN:
					this.reconnectAttempts--;
					LOGGER.info("Reconnection attempts left: ["+this.reconnectAttempts+"] of ["+FAILURE_RECONNECT_ATTEMPTS+"].");
					if (this.reconnectAttempts == 0)
					{
						this.connectionState = EConnectionState.CLOSE_WAIT;
						return;
					}
					
					this.connectionState = EConnectionState.RECONNECT;
					this.workerGroup.schedule(this::connect, FAILURE_RECONNECT_DELAY_SEC, TimeUnit.SECONDS);
					break;
					
				case RECONNECT_FORCE:
					LOGGER.info("Reconnecting forcefully.");
					this.reconnectAttempts = FAILURE_RECONNECT_ATTEMPTS;
					
					this.connectionState = EConnectionState.RECONNECT;
					this.workerGroup.schedule(this::connect, 0, TimeUnit.SECONDS);
					break;
			}
        });
    }

    /** Kills the current connection, triggering auto-reconnection immediately. */
    public void reconnect() 
	{
		this.connectionState = EConnectionState.RECONNECT_FORCE;
		this.channel.disconnect();
    }
	
	public final <TResponse extends FutureTrackableNetworkMessage> CompletableFuture<TResponse> sendRequest(FutureTrackableNetworkMessage msg)
	{
		return this.sendRequest(this.channel.pipeline().context(MessageHandler.class), msg);
	}
	
    @Override
    public void close() 
	{
        if (this.connectionState == EConnectionState.CLOSED)
		{
			return;
		}
		
		this.connectionState = EConnectionState.CLOSED;
		this.workerGroup.shutdownGracefully().syncUninterruptibly();
		this.channel.close().syncUninterruptibly();
		
		super.close();
    }
	
}
