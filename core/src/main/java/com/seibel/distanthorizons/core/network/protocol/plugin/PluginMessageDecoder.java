package com.seibel.distanthorizons.core.network.protocol.plugin;

import com.seibel.distanthorizons.core.network.messages.AbstractMessageRegistry;
import com.seibel.distanthorizons.core.network.messages.plugin.PluginMessageRegistry;
import com.seibel.distanthorizons.core.network.plugin.PluginChannelMessage;
import com.seibel.distanthorizons.core.network.protocol.INetworkObject;
import com.seibel.distanthorizons.core.network.protocol.MessageDecoder;
import com.seibel.distanthorizons.core.network.protocol.MessageEncoder;
import com.seibel.distanthorizons.coreapi.ModInfo;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.List;

public class PluginMessageDecoder extends MessageDecoder<PluginChannelMessage>
{
	public PluginMessageDecoder()
	{
		super(PluginMessageRegistry.INSTANCE);
	}
	
	public void decode(ByteBuf inputByteBuf, List<Object> outputDecodedObjectList)
	{
		int version = inputByteBuf.readShort();
		if (version != ModInfo.PLUGIN_PROTOCOL_VERSION)
		{
			return;
		}
		
		super.decode(null, inputByteBuf, outputDecodedObjectList);
	}
	
}
