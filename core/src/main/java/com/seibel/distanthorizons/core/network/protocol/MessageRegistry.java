package com.seibel.distanthorizons.core.network.protocol;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.seibel.distanthorizons.core.network.messages.*;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class MessageRegistry
{
	public static final MessageRegistry INSTANCE = new MessageRegistry();
	
    private final Map<Integer, Supplier<? extends NetworkMessage>> idToSupplier = new HashMap<>();
    private final BiMap<Class<? extends NetworkMessage>, Integer> classToId = HashBiMap.create();
	
	
	
	private MessageRegistry()
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
		
		// Full data requests
		this.registerMessage(FullDataSourceRequestMessage.class, FullDataSourceRequestMessage::new);
		this.registerMessage(FullDataSourceResponseMessage.class, FullDataSourceResponseMessage::new);
		
		// Generation task prioritization
		this.registerMessage(GenTaskPriorityRequestMessage.class, GenTaskPriorityRequestMessage::new);
		this.registerMessage(GenTaskPriorityResponseMessage.class, GenTaskPriorityResponseMessage::new);
	}
	
	
	
    public <T extends NetworkMessage> void registerMessage(Class<T> clazz, Supplier<T> supplier)
	{
		int id = this.idToSupplier.size() + 1;
		this.idToSupplier.put(id, supplier);
		this.classToId.put(clazz, id);
	}
	
    public Class<? extends NetworkMessage> getMessageClassById(int messageId) { return this.classToId.inverse().get(messageId); }
	
    public NetworkMessage createMessage(int messageId) throws IllegalArgumentException
	{
		try
		{
			return this.idToSupplier.get(messageId).get();
		}
		catch (NullPointerException e)
		{
			throw new IllegalArgumentException("Invalid message ID: "+messageId);
		}
	}
	
    public int getMessageId(NetworkMessage message) { return this.getMessageId(message.getClass()); }
	
    public int getMessageId(Class<? extends NetworkMessage> messageClass) {
		try
		{
			return this.classToId.get(messageClass);
		}
		catch (NullPointerException e)
		{
			throw new IllegalArgumentException("Message does not have ID assigned to it: "+messageClass.getSimpleName());
		}
	}
	
}
