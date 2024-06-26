package com.seibel.distanthorizons.core.wrapperInterfaces.misc;

import com.seibel.distanthorizons.core.network.messages.NetworkMessage;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;

public interface IPluginPacketSender extends IBindable
{
	void sendPluginPacketClient(NetworkMessage message);
	void sendPluginPacketServer(IServerPlayerWrapper serverPlayer, NetworkMessage message);
	
}