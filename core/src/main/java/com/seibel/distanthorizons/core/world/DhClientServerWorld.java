package com.seibel.distanthorizons.core.world;

import com.seibel.distanthorizons.core.file.structure.LocalSaveStructure;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.level.DhClientServerLevel;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.objects.EventLoop;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class DhClientServerWorld extends AbstractDhWorld implements IDhClientWorld, IDhServerWorld
{
    private final HashMap<ILevelWrapper, DhClientServerLevel> levelObjMap;
    private final HashSet<DhClientServerLevel> dhLevels;
    public final LocalSaveStructure saveStructure;
	
	// TODO why does this executor have 2 threads?
    public ExecutorService dhTickerThread = ThreadUtil.makeSingleThreadPool("DH Client Server World Ticker Thread", 2);
    public EventLoop eventLoop = new EventLoop(this.dhTickerThread, this::_clientTick); //TODO: Rate-limit the loop
	
    public F3Screen.DynamicMessage f3Message;
	
	
	
    public DhClientServerWorld()
	{
        super(EWorldEnvironment.Client_Server);
        this.saveStructure = new LocalSaveStructure();
		this.levelObjMap = new HashMap<>();
		this.dhLevels = new HashSet<>();
		
        LOGGER.info("Started DhWorld of type "+this.environment);
		
		this.f3Message = new F3Screen.DynamicMessage(() -> LodUtil.formatLog(this.environment+" World with "+this.dhLevels.size()+" levels"));
    }
	
	
	
    @Override
	public DhClientServerLevel getOrLoadLevel(ILevelWrapper wrapper)
	{
		if (wrapper instanceof IServerLevelWrapper)
		{
			return this.levelObjMap.computeIfAbsent(wrapper, (levelWrapper) -> 
			{
				File levelFile = this.saveStructure.getLevelFolder(levelWrapper);
				LodUtil.assertTrue(levelFile != null);
				DhClientServerLevel level = new DhClientServerLevel(this.saveStructure, (IServerLevelWrapper) levelWrapper);
				this.dhLevels.add(level);
				return level;
			});
		}
		else
		{
			return this.levelObjMap.computeIfAbsent(wrapper, (levelWrapper) ->
			{
				IClientLevelWrapper clientLevelWrapper = (IClientLevelWrapper) levelWrapper;
				IServerLevelWrapper serverLevelWrapper = clientLevelWrapper.tryGetServerSideWrapper();
				LodUtil.assertTrue(serverLevelWrapper != null);
				LodUtil.assertTrue(clientLevelWrapper.getDimensionType().equals(serverLevelWrapper.getDimensionType()), "tryGetServerSideWrapper returned a level for a different dimension. ClientLevelWrapper dim: " + clientLevelWrapper.getDimensionType().getDimensionName() + " ServerLevelWrapper dim: " + serverLevelWrapper.getDimensionType().getDimensionName());
				
				
				DhClientServerLevel level = this.levelObjMap.get(serverLevelWrapper);
				if (level == null)
				{
					return null;
				}
				
				level.startRenderer(clientLevelWrapper);
				return level;
			});
		}
	}

    @Override
    public DhClientServerLevel getLevel(ILevelWrapper wrapper) { return this.levelObjMap.get(wrapper); }
    
    @Override
    public Iterable<? extends IDhLevel> getAllLoadedLevels() { return this.dhLevels; }
    
    @Override
    public void unloadLevel(ILevelWrapper wrapper)
	{
		if (this.levelObjMap.containsKey(wrapper))
		{
			if (wrapper instanceof IServerLevelWrapper)
			{
				LOGGER.info("Unloading level "+this.levelObjMap.get(wrapper));
				DhClientServerLevel clientServerLevel = this.levelObjMap.remove(wrapper);
				this.dhLevels.remove(clientServerLevel);
				clientServerLevel.close();
			}
			else
			{
				// If the level wrapper is a Client Level Wrapper, then that means the client side leaves the level,
				// but note that the server side still has the level loaded. So, we don't want to unload the level,
				// we just want to stop rendering it.
				this.levelObjMap.remove(wrapper).stopRenderer(); // Ignore resource warning. The level obj is referenced elsewhere.
			}
		}
	}

    private void _clientTick()
	{
        //LOGGER.info("Client world tick with {} levels", levels.size());
        this.dhLevels.forEach(DhClientServerLevel::clientTick);
    }

    public void clientTick()
	{
        //LOGGER.info("Client world tick");
		this.eventLoop.tick();
    }
	
    public void serverTick() { this.dhLevels.forEach(DhClientServerLevel::serverTick); }
	
    public void doWorldGen() { this.dhLevels.forEach(DhClientServerLevel::doWorldGen); }
	
    @Override
    public CompletableFuture<Void> saveAndFlush() { return CompletableFuture.allOf(this.dhLevels.stream().map(DhClientServerLevel::saveAsync).toArray(CompletableFuture[]::new)); }
	
    @Override
    public void close()
	{
		// at this point the levels are probably unloaded, so this save call usually generally won't do anything
		this.saveAndFlush();
		this.f3Message.close();
		
		for (DhClientServerLevel level : this.dhLevels)
		{
			LOGGER.info("Unloading level "+level.getServerLevelWrapper().getDimensionType().getDimensionName());
			level.close();
		}
		
		this.levelObjMap.clear();
		this.eventLoop.close();
		LOGGER.info("Closed DhWorld of type "+this.environment);
	}
	
}
