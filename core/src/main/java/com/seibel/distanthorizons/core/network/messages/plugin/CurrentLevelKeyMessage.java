package com.seibel.distanthorizons.core.network.messages.plugin;

import com.google.common.base.MoreObjects;
import com.seibel.distanthorizons.core.network.plugin.PluginChannelMessage;
import io.netty.buffer.ByteBuf;

public class CurrentLevelKeyMessage extends PluginChannelMessage
{
	public String levelKey;
	public boolean deleteExistingData;
	
	public CurrentLevelKeyMessage() { }
	public CurrentLevelKeyMessage(String levelKey, boolean deleteExistingData)
	{
		this.levelKey = levelKey;
		this.deleteExistingData = deleteExistingData;
	}
	
	@Override
	public void encode(ByteBuf out)
	{
		this.writeString(this.levelKey, out);
		out.writeBoolean(this.deleteExistingData);
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		this.levelKey = this.readString(in);
		this.deleteExistingData = in.readBoolean();
	}
	
	
	@Override
	public MoreObjects.ToStringHelper toStringHelper()
	{
		return super.toStringHelper()
				.add("levelKey", this.levelKey)
				.add("deleteExistingData", this.deleteExistingData);
	}
	
}