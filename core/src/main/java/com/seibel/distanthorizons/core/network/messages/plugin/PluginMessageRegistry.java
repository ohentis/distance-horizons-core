package com.seibel.distanthorizons.core.network.messages.plugin;

import com.seibel.distanthorizons.core.network.messages.AbstractMessageRegistry;
import com.seibel.distanthorizons.core.network.messages.netty.base.NettyCloseEvent;
import com.seibel.distanthorizons.core.network.plugin.PluginChannelMessage;

public class PluginMessageRegistry extends AbstractMessageRegistry<PluginChannelMessage>
{
	public static final PluginMessageRegistry INSTANCE = new PluginMessageRegistry();
	
	private PluginMessageRegistry()
	{
		// Note: Messages must have parameterless constructors
		
		this.registerMessage(CurrentLevelKeyMessage.class, CurrentLevelKeyMessage::new);
		this.registerMessage(ServerConnectInfoMessage.class, ServerConnectInfoMessage::new);
	}
	
}
