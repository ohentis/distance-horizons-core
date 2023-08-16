package com.seibel.distanthorizons.core.wrapperInterfaces.misc;

import com.seibel.distanthorizons.api.interfaces.IDhApiUnsafeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.coreapi.util.math.Vec3d;

import java.util.UUID;

public interface IServerPlayerWrapper extends IDhApiUnsafeWrapper
{
	UUID getUUID();
	
	IServerLevelWrapper getLevel();
	
	Vec3d getPosition();
}
