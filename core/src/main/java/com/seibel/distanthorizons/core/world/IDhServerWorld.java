package com.seibel.distanthorizons.core.world;

import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;

/** Used both for dedicated server and singleplayer worlds */
public interface IDhServerWorld extends IDhWorld
{
	void serverTick();
	void doWorldGen();
	
	default IDhServerLevel getOrLoadServerLevel(ILevelWrapper levelWrapper) { return (IDhServerLevel) this.getOrLoadLevel(levelWrapper); }
	
}
