package com.seibel.distanthorizons.core.multiplayer.client;

import com.google.common.cache.CacheBuilder;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.multiplayer.config.SessionConfig;
import com.seibel.distanthorizons.core.network.INetworkObject;
import com.seibel.distanthorizons.core.network.event.ScopedNetworkEventSource;
import com.seibel.distanthorizons.core.network.event.internal.CloseInternalEvent;
import com.seibel.distanthorizons.core.network.event.internal.IncompatibleMessageInternalEvent;
import com.seibel.distanthorizons.core.network.messages.base.CurrentLevelKeyMessage;
import com.seibel.distanthorizons.core.network.messages.base.SessionConfigMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSplitMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataPartialUpdateMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataPayload;
import com.seibel.distanthorizons.core.network.session.NetworkSession;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.coreapi.ModInfo;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public class ClientNetworkState implements Closeable
{
	protected static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	
	
	private final ConcurrentMap<Integer, CompositeByteBuf> fullDataBufferById = CacheBuilder.newBuilder()
			.expireAfterAccess(10, TimeUnit.SECONDS)
			.<Integer, CompositeByteBuf>build()
			.asMap();
	
	private final SessionConfig.AnyChangeListener configAnyChangeListener = new SessionConfig.AnyChangeListener(this::sendConfigMessage);
	
	
	private final NetworkSession networkSession = new NetworkSession(null);
	/**
	 * Returns the client used by this instance. <p>
	 * If you need to subscribe to any packet events, create an instance of {@link ScopedNetworkEventSource} using the returned instance.
	 */
	public NetworkSession getSession() { return this.networkSession; }
	
	public SessionConfig sessionConfig = new SessionConfig();
	
	private volatile boolean configReceived = false;
	public boolean isReady() { return this.configReceived; }
	
	private EServerSupportStatus serverSupportStatus = EServerSupportStatus.NONE;
	
	/** Protocol version closest to supported by this mod version */
	@Nullable
	private Integer closestProtocolVersion;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public ClientNetworkState()
	{
		this.networkSession.registerHandler(IncompatibleMessageInternalEvent.class, event ->
		{
			if (this.closestProtocolVersion == null 
				|| Math.abs(event.protocolVersion - ModInfo.PROTOCOL_VERSION) < this.closestProtocolVersion)
			{
				this.closestProtocolVersion = event.protocolVersion;
			}
		});
		
		this.networkSession.registerHandler(CurrentLevelKeyMessage.class, message ->
		{
			// we will also receive this message when we have full support
			if (this.serverSupportStatus == EServerSupportStatus.NONE)
			{
				this.serverSupportStatus = EServerSupportStatus.LEVELS_ONLY;
			}
		});
		
		this.networkSession.registerHandler(SessionConfigMessage.class, message ->
		{
			this.serverSupportStatus = EServerSupportStatus.FULL;
			
			LOGGER.info("Connection config has been changed: ["+message.config+"].");
			this.sessionConfig = message.config;
			this.configReceived = true;
		});
		
		this.networkSession.registerHandler(CloseInternalEvent.class, message ->
		{
			this.configReceived = false;
		});
		
		this.networkSession.registerHandler(FullDataSplitMessage.class, message ->
		{
			if (message.isFirst)
			{
				CompositeByteBuf composite = this.fullDataBufferById.remove(message.bufferId);
				if (composite != null)
				{
					composite.release();
					LOGGER.debug("Released full data buffer ["+message.bufferId+"]: ["+composite+"]");
				}
			}
			
			CompositeByteBuf byteBuffer = this.fullDataBufferById.computeIfAbsent(message.bufferId, bufferId -> ByteBufAllocator.DEFAULT.compositeBuffer());
			byteBuffer.addComponent(true, message.buffer);
			LOGGER.debug("Full data buffer ["+message.bufferId+"]: ["+byteBuffer+"].");
		});

		this.networkSession.registerHandler(FullDataPartialUpdateMessage.class, msg ->
		{
			// Dummy handler to prevent unhandled message warnings
		});
	}
	
	
	
	//==============//
	// send message //
	//==============//
	
	public FullDataSourceV2DTO decodeDataSourceAndReleaseBuffer(FullDataPayload msg)
	{
		CompositeByteBuf compositeByteBuffer = this.fullDataBufferById.remove(msg.dtoBufferId);
		LodUtil.assertTrue(compositeByteBuffer != null);
		
		try
		{
			return INetworkObject.decodeToInstance(FullDataSourceV2DTO.CreateEmptyDataSource(), compositeByteBuffer);
		}
		finally
		{
			compositeByteBuffer.release();
		}
	}
	
	public void sendConfigMessage()
	{
		this.configReceived = false;
		this.getSession().sendMessage(new SessionConfigMessage(new SessionConfig()));
	}
	
	
	
	//===========//
	// debugging //
	//===========//
	
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		if (this.networkSession.isClosed())
		{
			messageList.add("NetworkSession closed: " + this.networkSession.getCloseReason().getMessage());
			return;
		}
		
		if (this.serverSupportStatus == EServerSupportStatus.NONE && this.closestProtocolVersion != null)
		{
			messageList.add("Incompatible protocol version: [" + this.closestProtocolVersion + "], required: [" + ModInfo.PROTOCOL_VERSION+ "]");
			return;
		}
		
		messageList.add(this.serverSupportStatus.message);
	}
	
	
	
	//==========//
	// shutdown //
	//==========//
	
	@Override
	public void close()
	{
		this.configAnyChangeListener.close();
		this.networkSession.close();
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	/**
	 * NONE,        <br>
	 * LEVELS_ONLY, <br>
	 * FULL,        <br>
	 */
	private enum EServerSupportStatus
	{
		NONE("Server does not support DH"),
		LEVELS_ONLY("Server supports shared level keys"),
		FULL("Server has full DH support");
		
		public final String message;
		
		EServerSupportStatus(String message) { this.message = message; }
		
	}
}