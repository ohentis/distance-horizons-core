package com.seibel.distanthorizons.core.world;

import com.seibel.distanthorizons.core.file.structure.LocalSaveStructure;
import com.seibel.distanthorizons.core.level.AbstractDhServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerState;
import com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerStateManager;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;

public abstract class AbstractDhServerWorld<TDhServerLevel extends AbstractDhServerLevel> extends AbstractDhWorld implements IDhServerWorld
{
	protected final HashMap<ILevelWrapper, TDhServerLevel> levelWrapperByDhLevel = new HashMap<>();
	public final LocalSaveStructure saveStructure = new LocalSaveStructure();
	
	private final ServerPlayerStateManager serverPlayerStateManager;
	
	public AbstractDhServerWorld(EWorldEnvironment worldEnvironment)
	{
		super(worldEnvironment);
		this.serverPlayerStateManager = new ServerPlayerStateManager();
	}
	
	
	//=================//
	// player handling //
	//=================//
	
	
	@Override
	public ServerPlayerStateManager getServerPlayerStateManager()
	{
		return this.serverPlayerStateManager;
	}
	
	@Override
	public void addPlayer(IServerPlayerWrapper serverPlayer)
	{
		ServerPlayerState playerState = this.serverPlayerStateManager.registerJoinedPlayer(serverPlayer);
		this.getLevel(serverPlayer.getLevel()).addPlayer(serverPlayer);
		
		for (TDhServerLevel level : this.levelWrapperByDhLevel.values())
		{
			level.registerNetworkHandlers(playerState);
		}
	}
	
	@Override
	public void removePlayer(IServerPlayerWrapper serverPlayer)
	{
		this.getLevel(serverPlayer.getLevel()).removePlayer(serverPlayer);
		this.serverPlayerStateManager.unregisterLeftPlayer(serverPlayer);
		
		// If player's left, session is already closed
	}
	
	@Override
	public void changePlayerLevel(IServerPlayerWrapper player, IServerLevelWrapper originLevel, IServerLevelWrapper destinationLevel)
	{
		this.getLevel(destinationLevel).addPlayer(player);
		this.getLevel(originLevel).removePlayer(player);
	}
	
	
	
	//================//
	// level handling //
	//================//
	
	@Override
	public TDhServerLevel getLevel(@NotNull ILevelWrapper wrapper) { return this.levelWrapperByDhLevel.get(wrapper); }
	@Override
	public Iterable<? extends IDhLevel> getAllLoadedLevels() { return this.levelWrapperByDhLevel.values(); }
	@Override
	public int getLoadedLevelCount() { return this.levelWrapperByDhLevel.size(); }
	
	
	
	//==============//
	// tick methods //
	//==============//
	
	@Override
	public void serverTick() { this.levelWrapperByDhLevel.values().forEach(TDhServerLevel::serverTick); }
	
	@Override
	public void worldGenTick() { this.levelWrapperByDhLevel.values().forEach(TDhServerLevel::worldGenTick); }
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public void close()
	{
		for (TDhServerLevel level : this.levelWrapperByDhLevel.values())
		{
			LOGGER.info("Unloading level [" + level.getLevelWrapper().getDimensionName() + "].");
			
			// level wrapper shouldn't be null, but just in case
			IServerLevelWrapper serverLevelWrapper = level.getServerLevelWrapper();
			if (serverLevelWrapper != null)
			{
				serverLevelWrapper.onUnload();
			}
			
			level.close();
		}
		
		this.levelWrapperByDhLevel.clear();
		LOGGER.info("Closed DhWorld of type [" + this.environment + "].");
	}
	
}
