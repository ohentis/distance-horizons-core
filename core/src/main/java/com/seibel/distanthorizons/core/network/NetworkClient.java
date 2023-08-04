package com.seibel.distanthorizons.core.network;

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.messages.CloseEvent;
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
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class NetworkClient extends NetworkEventSource implements AutoCloseable 
{
    private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
    private enum EConnectionState
	{
		INITIAL,
        OPEN,
        RECONNECT,
        RECONNECT_FORCE,
        CLOSE_WAIT,
        CLOSED
    }
	private static final Set<EConnectionState> closedStates = EnumSet.of(
			EConnectionState.CLOSE_WAIT,
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
	
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final Bootstrap clientBootstrap = new Bootstrap()
            .group(this.workerGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(new NetworkChannelInitializer(new MessageHandler(this::handleMessage)));
	
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
			this.connectionState = EConnectionState.CLOSE_WAIT;
        });
		
		this.registerHandler(CloseEvent.class, closeEvent ->
		{
            LOGGER.info("Disconnected from server: "+ closeEvent.getChannelContext().channel().remoteAddress());
            if (this.connectionState == EConnectionState.CLOSE_WAIT)
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
        connectFuture.addListener((ChannelFuture channelFuture) -> 
		{
            if (!channelFuture.isSuccess())
			{
				LOGGER.warn("Connection failed: "+channelFuture.cause());
				return;
			}
			
			channel.writeAndFlush(new HelloMessage());
			ready = true;
        });
		
		this.channel = connectFuture.channel();
		this.channel.closeFuture().addListener((ChannelFuture channelFuture) ->
		{
			ready = false;
			this.completeAllFuturesExceptionally(channelFuture.cause() != null
					? channelFuture.cause()
					: new ChannelException("Channel is closed."));
			
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
