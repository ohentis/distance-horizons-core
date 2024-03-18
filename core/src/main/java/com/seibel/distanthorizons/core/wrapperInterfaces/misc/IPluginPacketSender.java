package com.seibel.distanthorizons.core.wrapperInterfaces.misc;

import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public interface IPluginPacketSender extends IBindable
{
	void sendPluginPacket(@Nullable IServerPlayerWrapper serverPlayer, Consumer<ByteBuf> encoder);
	
}
