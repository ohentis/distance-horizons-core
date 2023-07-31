package com.seibel.distanthorizons.core.world;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.file.structure.ClientOnlySaveStructure;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.level.DhClientServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.network.NetworkClient;
import com.seibel.distanthorizons.core.network.messages.AckMessage;
import com.seibel.distanthorizons.core.network.messages.HelloMessage;
import com.seibel.distanthorizons.core.network.messages.PlayerUUIDMessage;
import com.seibel.distanthorizons.core.network.messages.RemotePlayerConfigMessage;
import com.seibel.distanthorizons.core.network.objects.RemotePlayer;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.objects.EventLoop;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

public class DhClientWorld extends AbstractDhWorld implements IDhClientWorld
{
    private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);

    private final ConcurrentHashMap<IClientLevelWrapper, DhClientLevel> levels;
    public final ClientOnlySaveStructure saveStructure;

    private final NetworkClient networkClient;

	// TODO why does this executor have 2 threads?
    public ExecutorService dhTickerThread = ThreadUtil.makeSingleThreadPool("DH Client World Ticker Thread", 2);
    public EventLoop eventLoop = new EventLoop(this.dhTickerThread, this::_clientTick);
	
	
	
	//==============//
	// constructors //
	//==============//
	
    public DhClientWorld()
	{
		super(EWorldEnvironment.Client_Only);

        this.saveStructure = new ClientOnlySaveStructure();
        this.levels = new ConcurrentHashMap<>();

		if (Config.Client.Advanced.Multiplayer.enableMultiverseNetworking.get())
		{
			// TODO server specific configs
			this.networkClient = new NetworkClient(MC_CLIENT.getCurrentServerIp(), 25049);
			this.registerNetworkHandlers();
		}
		else
		{
			this.networkClient = null;
		}

		LOGGER.info("Started DhWorld of type "+this.environment);
	}

    private void registerNetworkHandlers() {
		this.networkClient.registerHandler(HelloMessage.class, helloMessage ->
		{
			LOGGER.info("Connected to server: "+helloMessage.getChannelContext().channel().remoteAddress());
			
			this.networkClient.sendRequest(new PlayerUUIDMessage(MC_CLIENT.getPlayerUUID()))
					.thenCompose(ack -> this.networkClient.<RemotePlayerConfigMessage>sendRequest(new RemotePlayerConfigMessage(new RemotePlayer.Payload())))
					.thenAccept(responseConfigMsg -> {
						// TODO do something with received config
					})
					.exceptionally(throwable -> {
						LOGGER.error("Error while fetching server's config", throwable);
						return null;
					});
		});
    }
	
	
	
	//=========//
	// methods //
	//=========//
	
    @Override
    public DhClientLevel getOrLoadLevel(ILevelWrapper wrapper)
	{
        if (!(wrapper instanceof IClientLevelWrapper))
		{
			return null;
		}

        return this.levels.computeIfAbsent((IClientLevelWrapper) wrapper, (clientLevelWrapper) ->
		{
            File file = this.saveStructure.getLevelFolder(wrapper);

            if (file == null)
			{
				return null;
			}

			return new DhClientLevel(this.saveStructure, clientLevelWrapper, networkClient);
        });
    }

    @Override
    public DhClientLevel getLevel(ILevelWrapper wrapper)
	{
        if (!(wrapper instanceof IClientLevelWrapper))
		{
			return null;
		}

        return this.levels.get(wrapper);
    }

    @Override
    public Iterable<? extends IDhLevel> getAllLoadedLevels() { return this.levels.values(); }

    @Override
    public void unloadLevel(ILevelWrapper wrapper)
	{
        if (!(wrapper instanceof IClientLevelWrapper))
		{
			return;
		}

        if (this.levels.containsKey(wrapper))
		{
            LOGGER.info("Unloading level "+this.levels.get(wrapper));
			this.levels.remove(wrapper).close();
        }
    }

    private void _clientTick()
	{
		this.levels.values().forEach(DhClientLevel::clientTick);
	}

    public void clientTick() { this.eventLoop.tick(); }
	
	public void doWorldGen() { this.levels.values().forEach(DhClientLevel::doWorldGen); }

    @Override
    public CompletableFuture<Void> saveAndFlush()
	{
        return CompletableFuture.allOf(this.levels.values().stream().map(DhClientLevel::saveAsync).toArray(CompletableFuture[]::new));
    }

    @Override
    public void close()
	{
		if (this.networkClient != null)
		{
			this.networkClient.close();
		}
  

		this.saveAndFlush();
        for (DhClientLevel dhClientLevel : this.levels.values())
		{
            LOGGER.info("Unloading level " + dhClientLevel.getLevelWrapper().getDimensionType().getDimensionName());
            dhClientLevel.close();
        }

		this.levels.clear();
		this.eventLoop.close();
        LOGGER.info("Closed DhWorld of type "+this.environment);
    }

}
