package com.seibel.distanthorizons.core.multiplayer.client;

import com.google.common.cache.CacheBuilder;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfig;
import com.seibel.distanthorizons.core.multiplayer.config.MultiplayerConfigChangeListener;
import com.seibel.distanthorizons.core.network.INetworkObject;
import com.seibel.distanthorizons.core.network.event.ScopedNetworkEventSource;
import com.seibel.distanthorizons.core.network.event.CloseEvent;
import com.seibel.distanthorizons.core.network.messages.base.RemotePlayerConfigMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataChunkMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataPayload;
import com.seibel.distanthorizons.core.network.session.Session;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import org.apache.logging.log4j.LogManager;

import java.io.Closeable;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public class ClientNetworkState implements Closeable
{
	protected static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	private final Session session = new Session(null);
	private EServerSupportStatus serverSupportStatus = EServerSupportStatus.NONE;
	
	
	public MultiplayerConfig config = new MultiplayerConfig();
	private volatile boolean configReceived = false;
	private final MultiplayerConfigChangeListener configChangeListener = new MultiplayerConfigChangeListener(this::sendConfigMessage);
	public boolean isReady() { return this.configReceived; }
	
	/**
	 * Returns the client used by this instance. <p>
	 * If you need to subscribe to any packet events, create an instance of {@link ScopedNetworkEventSource} using the returned instance.
	 */
	public Session getSession() { return this.session; }
	
	private final ConcurrentMap<Integer, CompositeByteBuf> fullDataBuffers = CacheBuilder.newBuilder()
			.expireAfterAccess(10, TimeUnit.SECONDS)
			.<Integer, CompositeByteBuf>build()
			.asMap();
	
	
	/**
	 * Constructs a new instance.
	 */
	public ClientNetworkState()
	{
		this.session.registerHandler(RemotePlayerConfigMessage.class, msg ->
		{
			this.serverSupportStatus = EServerSupportStatus.FULL;
			
			LOGGER.info("Connection config has been changed: " + msg.payload);
			this.config = (MultiplayerConfig) msg.payload;
			this.configReceived = true;
		});
		
		this.session.registerHandler(CloseEvent.class, msg ->
		{
			this.configReceived = false;
		});
		
		this.session.registerHandler(FullDataChunkMessage.class, msg ->
		{
			if (msg.isFirst)
			{
				CompositeByteBuf composite = this.fullDataBuffers.remove(msg.bufferId);
				if (composite != null)
				{
					composite.release();
					LOGGER.debug("Released full data buffer {}: {}", msg.bufferId, composite);
				}
			}
			
			CompositeByteBuf composite = this.fullDataBuffers.computeIfAbsent(msg.bufferId, bufferId -> ByteBufAllocator.DEFAULT.compositeBuffer());
			composite.addComponent(true, msg.buffer);
			LOGGER.debug("Full data buffer {}: {}", msg.bufferId, composite);
		});
	}
	
	public FullDataSourceV2DTO decodeDataSourceAndReleaseBuffer(FullDataPayload msg)
	{
		CompositeByteBuf composite = this.fullDataBuffers.remove(msg.dtoBufferId);
		Objects.requireNonNull(composite);
		
		try
		{
			return INetworkObject.decodeToInstance(new FullDataSourceV2DTO(), composite);
		}
		finally
		{
			composite.release();
		}
	}
	
	public void sendConfigMessage()
	{
		this.configReceived = false;
		this.getSession().sendMessage(new RemotePlayerConfigMessage(new MultiplayerConfig()));
	}
	
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		if (this.session.isClosed())
		{
			messageList.add("Session closed: " + this.session.getCloseReason().getMessage());
			return;
		}
		
		messageList.add(this.serverSupportStatus.message);
	}
	
	@Override
	public void close()
	{
		this.configChangeListener.close();
		this.session.close();
	}
	
	private enum EServerSupportStatus
	{
		NONE("Server does not support DH"),
		LEVELS_ONLY("Server supports shared level keys"),
		FULL("Server has full DH support");
		
		public final String message;
		
		EServerSupportStatus(String message)
		{
			this.message = message;
		}
	}
}