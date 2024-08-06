package com.seibel.distanthorizons.core.network.messages;

import com.google.common.base.MoreObjects;
import com.seibel.distanthorizons.core.network.INetworkObject;
import com.seibel.distanthorizons.core.network.session.Session;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;

public abstract class NetworkMessage implements INetworkObject
{
	public Session session = null;
	public IServerPlayerWrapper serverPlayer() { return this.session.serverPlayer; }
	
	public Session getConnection()
	{
		return this.session;
	}
	
	public void setSession(Session connection)
	{
		if (this.session != null)
		{
			throw new IllegalStateException("Session object cannot be changed after initialization.");
		}
		this.session = connection;
	}
	
	
	@Override
	public String toString()
	{
		return this.toStringHelper().toString();
	}
	
	public MoreObjects.ToStringHelper toStringHelper()
	{
		return MoreObjects.toStringHelper(this);
	}
	
}