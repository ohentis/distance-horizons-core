package com.seibel.distanthorizons.core.network.messages;

import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;

public interface ILevelRelatedMessage
{
	String getLevelName();
	
	/**
	 * Checks whether the message's level matches the given level.
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	default boolean isSameLevelAs(IServerLevelWrapper levelWrapper)
	{
		return this.getLevelName().equals(levelWrapper.getKeyedLevelDimensionName());
	}
	
}