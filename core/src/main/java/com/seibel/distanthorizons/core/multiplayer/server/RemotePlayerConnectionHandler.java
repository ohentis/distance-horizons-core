package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.network.exceptions.InvalidLevelException;
import com.seibel.distanthorizons.core.network.messages.plugin.ILevelRelatedMessage;
import com.seibel.distanthorizons.core.network.netty.NettyMessage;
import com.seibel.distanthorizons.core.network.plugin.TrackableNettyMessage;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.LogManager;

import java.io.Closeable;
import java.text.MessageFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class RemotePlayerConnectionHandler implements Closeable
{
	private static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logNetworkEvent.get());
	private static final ConfigEntry<Boolean> GENERATE_MULTIPLE_DIMENSIONS_CONFIG = Config.Client.Advanced.Multiplayer.ServerNetworking.generateMultipleDimensions;
	
	private final ConcurrentMap<IServerPlayerWrapper, ServerPlayerState> connectedPlayers = new ConcurrentHashMap<>();
	
	
	public void handlePluginMessage(IServerPlayerWrapper player, ByteBuf buffer)
	{
		this.connectedPlayers.get(player).session.decodeAndHandle(buffer);
	}
	
	
	public <T extends NettyMessage> Consumer<T> currentLevelOnly(DhServerLevel level, BiConsumer<T, ServerPlayerState> next)
	{
		return (msg) ->
		{
			LodUtil.assertTrue(msg instanceof ILevelRelatedMessage, "Received message does not implement " + ILevelRelatedMessage.class.getSimpleName() + ": " + msg.getClass().getSimpleName());
			
			// Handle only in requested dimension
			if (!((ILevelRelatedMessage) msg).isSameLevelAs(level.getLevelWrapper()))
			{
				return;
			}
			
			// If player is not in this dimension and handling multiple dimensions at once is not allowed
			if (serverPlayerState.serverPlayer.getLevel() != level.getLevelWrapper()
					&& !GENERATE_MULTIPLE_DIMENSIONS_CONFIG.get())
			{
				// If the message can be replied to - reply with error, otherwise just ignore
				if (msg instanceof TrackableNettyMessage)
				{
					((TrackableNettyMessage) msg).sendResponse(new InvalidLevelException(MessageFormat.format(
							"Generation not allowed. Requested dimension: {0}, player dimension: {1}",
							level.getLevelWrapper().getDimensionType().getDimensionName(),
							serverPlayerState.serverPlayer.getLevel().getDimensionType().getDimensionName()
					)));
				}
				
				return;
			}
			
			next.accept(msg, serverPlayerState);
		};
	}
	
	public void registerJoinedPlayer(IServerPlayerWrapper serverPlayer)
	{
		this.connectedPlayers.put(serverPlayer, new ServerPlayerState(serverPlayer));
	}
	
	public void unregisterLeftPlayer(IServerPlayerWrapper serverPlayer)
	{
		ServerPlayerState playerState = this.connectedPlayers.remove(serverPlayer);
		if (playerState != null)
		{
			playerState.close();
		}
	}
	
	@Override
	public void close()
	{
		this.configChangeListener.close();
	}
	
}