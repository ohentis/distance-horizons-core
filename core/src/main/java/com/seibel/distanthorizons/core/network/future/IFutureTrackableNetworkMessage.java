package com.seibel.distanthorizons.core.network.future;

import com.seibel.distanthorizons.core.network.protocol.INetworkMessage;

public interface IFutureTrackableNetworkMessage<TKey extends Comparable<TKey>> extends INetworkMessage
{
	TKey getRequestKey();
}
