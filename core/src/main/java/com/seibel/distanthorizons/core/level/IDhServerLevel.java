package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataFileHandler;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;

public interface IDhServerLevel extends IDhWorldGenLevel
{
    void serverTick();
	
    IServerLevelWrapper getServerLevelWrapper();
}
