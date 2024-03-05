package com.seibel.distanthorizons.core.network.protocol.plugin;

import com.seibel.distanthorizons.core.network.messages.plugin.PluginMessageRegistry;
import com.seibel.distanthorizons.core.network.plugin.PluginChannelMessage;
import com.seibel.distanthorizons.core.network.protocol.MessageEncoder;
import com.seibel.distanthorizons.coreapi.ModInfo;
import io.netty.buffer.ByteBuf;

public class PluginMessageEncoder extends MessageEncoder<PluginChannelMessage>
{
	public PluginMessageEncoder()
	{
		super(PluginMessageRegistry.INSTANCE, PluginChannelMessage.class);
	}
	
	public void encode(PluginChannelMessage pluginChannelMessage, ByteBuf outputByteBuf) throws IllegalArgumentException
	{
		outputByteBuf.writeShort(ModInfo.PLUGIN_PROTOCOL_VERSION);
		super.encode(null, pluginChannelMessage, outputByteBuf);
	}
	
}
