package com.seibel.distanthorizons.core.wrapperInterfaces.misc;

import com.seibel.distanthorizons.api.interfaces.IDhApiUnsafeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;

import java.util.UUID;

public interface IServerPlayerWrapper extends IDhApiUnsafeWrapper
{
    UUID getUUID();

    IServerLevelWrapper getLevel();
}
