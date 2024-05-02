package com.seibel.distanthorizons.core.wrapperInterfaces.misc;

import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;
import io.netty.buffer.ByteBuf;

import java.util.function.Consumer;

public interface IPluginPacketSender extends IBindable
{
	void sendPluginPacketClient(Consumer<ByteBuf> encoder);
	void sendPluginPacketServer(IServerPlayerWrapper serverPlayer, Consumer<ByteBuf> encoder);
	
}