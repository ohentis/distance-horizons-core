package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataFileHandler;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;

public interface IDhServerLevel extends IDhLevel, GeneratedFullDataFileHandler.IOnWorldGenCompleteListener
{
    void serverTick();
    void doWorldGen();
	
    IServerLevelWrapper getServerLevelWrapper();
	
}
