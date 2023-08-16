package com.seibel.distanthorizons.core.world;

import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;

public interface IDhClientWorld extends IDhWorld
{
	void clientTick();
	
	void doWorldGen();
	
	default IDhClientLevel getOrLoadClientLevel(ILevelWrapper levelWrapper) { return (IDhClientLevel) this.getOrLoadLevel(levelWrapper); }
	
}
