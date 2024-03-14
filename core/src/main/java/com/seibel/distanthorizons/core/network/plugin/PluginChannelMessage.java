package com.seibel.distanthorizons.core.network.plugin;

import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;

public abstract class PluginChannelMessage implements INetworkObject
{
	public IServerPlayerWrapper serverPlayer;
}
