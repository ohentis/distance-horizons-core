package com.seibel.distanthorizons.core.network.messages.netty;

import com.seibel.distanthorizons.core.network.messages.AbstractMessageRegistry;
import com.seibel.distanthorizons.core.network.messages.netty.base.*;
import com.seibel.distanthorizons.core.network.messages.netty.fullData.FullDataPartialUpdateMessage;
import com.seibel.distanthorizons.core.network.messages.netty.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.netty.fullData.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.network.messages.netty.fullData.generation.GenTaskPriorityRequestMessage;
import com.seibel.distanthorizons.core.network.messages.netty.fullData.generation.GenTaskPriorityResponseMessage;
import com.seibel.distanthorizons.core.network.messages.netty.session.PlayerUUIDMessage;
import com.seibel.distanthorizons.core.network.messages.netty.session.RemotePlayerConfigMessage;
import com.seibel.distanthorizons.core.network.netty.NettyMessage;

public class NettyMessageRegistry extends AbstractMessageRegistry<NettyMessage>
{
	public static final NettyMessageRegistry INSTANCE = new NettyMessageRegistry();
	
	private NettyMessageRegistry()
	{
		// Note: Messages must have parameterless constructors
		
		// Opening & closing connection
		// These messages should be compatible with any previous protocol versions
		this.registerMessage(HelloMessage.class, HelloMessage::new);
		this.registerMessage(CloseReasonMessage.class, CloseReasonMessage::new);
		
		// Core
		this.registerMessage(AckMessage.class, AckMessage::new);
		this.registerMessage(CancelMessage.class, CancelMessage::new);
		this.registerMessage(ExceptionMessage.class, ExceptionMessage::new);
		
		// ID & config
		this.registerMessage(PlayerUUIDMessage.class, PlayerUUIDMessage::new);
		this.registerMessage(RemotePlayerConfigMessage.class, RemotePlayerConfigMessage::new);
		
		// Full data requests & updates
		this.registerMessage(FullDataSourceRequestMessage.class, FullDataSourceRequestMessage::new);
		this.registerMessage(FullDataSourceResponseMessage.class, FullDataSourceResponseMessage::new);
		this.registerMessage(FullDataPartialUpdateMessage.class, FullDataPartialUpdateMessage::new);
		
		// Generation task prioritization
		this.registerMessage(GenTaskPriorityRequestMessage.class, GenTaskPriorityRequestMessage::new);
		this.registerMessage(GenTaskPriorityResponseMessage.class, GenTaskPriorityResponseMessage::new);
	}
	
}
