package com.seibel.distanthorizons.core.network.messages.base;

import com.seibel.distanthorizons.core.network.exceptions.InvalidLevelException;
import com.seibel.distanthorizons.core.network.protocol.FutureTrackableNetworkMessage;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;

public interface ILevelRelatedMessage
{
	int getLevelHashCode();
	
	/**
	 * Returns true if level does not match the given level.
	 * 
	 * @param levelWrapper Level wrapper to check against.
	 * @return Whether the level is invalid.
	 */
	default boolean isLevelInvalid(ILevelWrapper levelWrapper)
	{
		return levelWrapper.getDimensionType().getDimensionName().hashCode() != getLevelHashCode();
	}
	
	/**
	 * Same as {@link #isLevelInvalid}.
	 * If current message implements {@link FutureTrackableNetworkMessage}, additionally sends an exception response if given wrapper does not match.
	 * 
	 * @param levelWrapper Level wrapper to check against.
	 * @return Whether the level is invalid.
	 */
	default boolean sendExceptionIfLevelInvalid(ILevelWrapper levelWrapper)
	{
		if (isLevelInvalid(levelWrapper))
		{
			if (this instanceof FutureTrackableNetworkMessage)
				((FutureTrackableNetworkMessage) this).sendResponse(new InvalidLevelException("Invalid level"));
			return true;
		}
		
		return false;
	}
	
}
