package com.seibel.distanthorizons.core.wrapperInterfaces.misc;

import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.Nullable;

public interface IPluginPacketSender extends IBindable
{
	void sendPluginPacket(@Nullable IServerPlayerWrapper serverPlayer, ByteBuf buffer);
	
}
