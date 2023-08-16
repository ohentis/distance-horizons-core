package com.seibel.distanthorizons.core.file.structure;

import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;

import java.io.File;

/**
 * Designed for Client_Server & Server_Only environments.
 *
 * @version 2022-12-17
 */
public class LocalSaveStructure extends AbstractSaveStructure
{
	public static final String SERVER_FOLDER_NAME = "Distant_Horizons";
	
	private File debugPath = new File("");
	
	
	
	public LocalSaveStructure() { }
	
	
	
	//================//
	// folder methods //
	//================//
	
	@Override
	public File getLevelFolder(ILevelWrapper wrapper)
	{
		IServerLevelWrapper serverSide = (IServerLevelWrapper) wrapper;
		this.debugPath = new File(serverSide.getSaveFolder(), "Distant_Horizons");
		return new File(serverSide.getSaveFolder(), "Distant_Horizons");
	}
	
	@Override
	public File getRenderCacheFolder(ILevelWrapper level)
	{
		IServerLevelWrapper serverSide = (IServerLevelWrapper) level;
		this.debugPath = new File(serverSide.getSaveFolder(), "Distant_Horizons");
		return new File(new File(serverSide.getSaveFolder(), "Distant_Horizons"), RENDER_CACHE_FOLDER);
	}
	
	@Override
	public File getFullDataFolder(ILevelWrapper level)
	{
		IServerLevelWrapper serverLevelWrapper = (IServerLevelWrapper) level;
		this.debugPath = new File(serverLevelWrapper.getSaveFolder(), SERVER_FOLDER_NAME);
		return new File(new File(serverLevelWrapper.getSaveFolder(), SERVER_FOLDER_NAME), DATA_FOLDER);
	}
	
	
	
	//==================//
	// override methods //
	//==================//
	
	@Override
	public void close() throws Exception { }
	
	@Override
	public String toString() { return "[" + this.getClass().getSimpleName() + "@" + this.debugPath + "]"; }
	
}
