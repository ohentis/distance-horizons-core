package com.seibel.distanthorizons.core.network.messages.base;

import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;

public interface ILevelRelatedMessage
{
	int getLevelHashCode();
	
	/**
	 * Checks whether the message's level matches the given level.
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	default boolean isLevelValid(ILevelWrapper levelWrapper)
	{
		return levelWrapper.getDimensionType().getDimensionName().hashCode() == this.getLevelHashCode();
	}
	
}
