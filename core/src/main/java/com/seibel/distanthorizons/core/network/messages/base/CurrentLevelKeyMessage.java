package com.seibel.distanthorizons.core.network.messages.base;

import com.google.common.base.MoreObjects;
import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import io.netty.buffer.ByteBuf;

public class CurrentLevelKeyMessage extends AbstractNetworkMessage
{
	public String levelKey;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public CurrentLevelKeyMessage() { }
	public CurrentLevelKeyMessage(String levelKey) { this.levelKey = levelKey; }
	
	
	
	//===============//
	// serialization //
	//===============//
	
	@Override
	public void encode(ByteBuf out) { this.writeString(this.levelKey, out); }
	
	@Override
	public void decode(ByteBuf in) { this.levelKey = this.readString(in); }
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public MoreObjects.ToStringHelper toStringHelper()
	{
		return super.toStringHelper()
				.add("levelKey", this.levelKey);
	}
	
}