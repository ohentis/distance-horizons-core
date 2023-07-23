/*
 *    This file is part of the Distant Horizons mod (formerly the LOD Mod),
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2022  James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.api.internal;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.*;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.level.IServerKeyedClientLevel;
import com.seibel.distanthorizons.core.level.IKeyedClientLevelManager;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.world.*;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.api.enums.rendering.EDebugRendering;
import com.seibel.distanthorizons.api.enums.rendering.ERendererMode;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.logging.ConfigBasedSpamLogger;
import com.seibel.distanthorizons.core.logging.SpamReducedLogger;
import com.seibel.distanthorizons.coreapi.util.math.Mat4f;
import com.seibel.distanthorizons.core.render.glObject.GLProxy;
import com.seibel.distanthorizons.core.render.renderer.TestRenderer;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * This holds the methods that should be called
 * by the host mod loader (Fabric, Forge, etc.).
 * Specifically for the client.
 */
public class ClientApi
{
	private static final Logger LOGGER = LogManager.getLogger();
	public static final boolean ENABLE_EVENT_LOGGING = true;
	public static boolean prefLoggerEnabled = false;

	public static final ClientApi INSTANCE = new ClientApi();
	public static TestRenderer testRenderer = new TestRenderer();
	private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final IKeyedClientLevelManager KEYED_CLIENT_LEVEL_MANAGER = SingletonInjector.INSTANCE.get(IKeyedClientLevelManager.class);

	public static final long SPAM_LOGGER_FLUSH_NS = TimeUnit.NANOSECONDS.convert(1, TimeUnit.SECONDS);

	private boolean configOverrideReminderPrinted = false;
	public boolean rendererDisabledBecauseOfExceptions = false;

	private long lastFlushNanoTime = 0;

	private boolean isServerCommunicationEnabled = true;

	private boolean serverIsMalformed = false;


	//==============//
	// constructors //
	//==============//

	private ClientApi()
	{

	}



	//========//
	// events //
	//========//

	public void onClientOnlyConnected()
	{
		// only continue if the client is connected to a different server
		if (MC.clientConnectedToDedicatedServer())
		{
			if (ENABLE_EVENT_LOGGING)
			{
				LOGGER.info("Client on ClientOnly mode connecting.");
			}

			SharedApi.setDhWorld(new DhClientWorld());
		}
	}

	public void onClientOnlyDisconnected()
	{
		if (MC.clientConnectedToDedicatedServer())
		{
			AbstractDhWorld world = SharedApi.getAbstractDhWorld();
			if (world != null)
			{
				if (ENABLE_EVENT_LOGGING)
				{
					LOGGER.info("Client on ClientOnly mode disconnecting.");
				}

				world.close();
				SharedApi.setDhWorld(null);
			}
			this.isServerCommunicationEnabled = false;
			this.serverIsMalformed = false;
			KEYED_CLIENT_LEVEL_MANAGER.setUseOverrideWrapper(false);
			KEYED_CLIENT_LEVEL_MANAGER.setServerKeyedLevel(null);
		}
	}

	public void clientChunkLoadEvent(IChunkWrapper chunk, IClientLevelWrapper level) { this.applyChunkUpdate(chunk, level); }
	public void clientChunkSaveEvent(IChunkWrapper chunk, IClientLevelWrapper level) { this.applyChunkUpdate(chunk, level); }
	private void applyChunkUpdate(IChunkWrapper chunk, IClientLevelWrapper level)
	{
		// if the user is in a single player world the chunk updates are handled on the server side
		if (SharedApi.getEnvironment() != EWorldEnvironment.Client_Only)
		{
			return;
		}
		
		// only continue if the level is still loaded
		IDhLevel dhLevel = SharedApi.getAbstractDhWorld().getLevel(level);
		if (dhLevel == null)
		{
			return;
		}
		
		
		dhLevel.updateChunkAsync(chunk);
		
		// also update any existing neighbour chunks so lighting changes are propagated correctly
		for (int xOffset = -1; xOffset <= 1; xOffset++)
		{
			for (int zOffset = -1; zOffset <= 1; zOffset++)
			{
				DhChunkPos neighbourPos = new DhChunkPos(chunk.getChunkPos().x+xOffset, chunk.getChunkPos().z+zOffset);
				IChunkWrapper neighbourChunk =  dhLevel.getLevelWrapper().tryGetChunk(neighbourPos);
				if (neighbourChunk != null)
				{
					dhLevel.updateChunkAsync(neighbourChunk);
				}
			}
		}
	}
	

	public void clientLevelUnloadEvent(IClientLevelWrapper level)
	{
		if (ENABLE_EVENT_LOGGING)
		{
			LOGGER.info("Client level "+level+" unloading.");
		}

		AbstractDhWorld world = SharedApi.getAbstractDhWorld();
		if (world != null)
		{
			world.unloadLevel(level);
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent.EventParam(level));
		}
	}

	public void clientLevelLoadEvent(IClientLevelWrapper level)
	{
		if (this.isServerCommunicationEnabled)
		{
			if (ENABLE_EVENT_LOGGING)
			{
				LOGGER.info("Server supports communication, deferring loading.");
			}
			return;
		}
		
		
		if (ENABLE_EVENT_LOGGING)
		{
			LOGGER.info("Client level " + level + " loading.");
		}
		
		AbstractDhWorld world = SharedApi.getAbstractDhWorld();
		if (world != null)
		{
			world.getOrLoadLevel(level);
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent.EventParam(level));
		}
	}

	public void serverLevelLoadEvent(IServerKeyedClientLevel level)
	{
		if (ENABLE_EVENT_LOGGING)
		{
			LOGGER.info("Server level " + level + " (" + level.getServerLevelKey() + ") loading.");
		}

		AbstractDhWorld world = SharedApi.getAbstractDhWorld();
		if (world != null)
		{
			world.getOrLoadLevel(level);
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent.EventParam(level));
		}
	}

	public void rendererShutdownEvent()
	{
		if (ENABLE_EVENT_LOGGING)
		{
			LOGGER.info("Renderer shutting down.");
		}

		IProfilerWrapper profiler = MC.getProfiler();
		profiler.push("DH-RendererShutdown");

		profiler.pop();
	}

	public void rendererStartupEvent()
	{
		if (ENABLE_EVENT_LOGGING)
		{
			LOGGER.info("Renderer starting up.");
		}

		IProfilerWrapper profiler = MC.getProfiler();
		profiler.push("DH-RendererStartup");

		// make sure the GLProxy is created before the LodBufferBuilder needs it
		GLProxy.getInstance();
		profiler.pop();
	}

	public void clientTickEvent()
	{
		IProfilerWrapper profiler = MC.getProfiler();
		profiler.push("DH-ClientTick");

		boolean doFlush = System.nanoTime() - this.lastFlushNanoTime >= SPAM_LOGGER_FLUSH_NS;
		if (doFlush)
		{
			this.lastFlushNanoTime = System.nanoTime();
			SpamReducedLogger.flushAll();
		}
		ConfigBasedLogger.updateAll();
		ConfigBasedSpamLogger.updateAll(doFlush);

		IDhClientWorld clientWorld = SharedApi.getIDhClientWorld();
		if (clientWorld != null)
		{
			clientWorld.clientTick();
		}
		profiler.pop();
	}
	
	/** @param byteBuf is Netty's {@link ByteBuffer} wrapper. */
	public void serverMessageReceived(ByteBuf byteBuf)
	{
		// It is important to ensure malicious server input is ignored.
		if (this.serverIsMalformed)
		{
			return;
		}
		
		short commandLength = byteBuf.readShort();
		if (commandLength > 32) // TODO 32 should be put into a constant somewhere
		{
			LOGGER.error("Server sent command > 32");
			ClientApi.INSTANCE.serverIsMalformed = true;
			return;
		}
		
		String eventType = byteBuf.readCharSequence(commandLength, StandardCharsets.UTF_8).toString();
		switch (eventType)
		{
			case "ServerCommsEnabled":
				LOGGER.info("Server supports DH protocol.");
				ClientApi.INSTANCE.isServerCommunicationEnabled = true;
				KEYED_CLIENT_LEVEL_MANAGER.setUseOverrideWrapper(true);
				MC.executeOnRenderThread(() -> {
					// Go ahead and unload the current world, because it may be wrong. We expect
					// a followup WorldChanged event from the server soon anyways.
					this.clientLevelUnloadEvent((IClientLevelWrapper) MC.getWrappedClientWorld());
				});
				break;
				
			case "WorldChanged":
				short worldKeyLength = byteBuf.readShort();
				if (worldKeyLength > 128) // TODO 128 should be put into a constant somewhere
				{
					LOGGER.error("Server sent worldKey > 128");
					this.serverIsMalformed = true;
					return;
				}
				
				String worldKey = byteBuf.readCharSequence(worldKeyLength, StandardCharsets.UTF_8).toString();
				if (!worldKey.matches("[a-zA-Z0-9_]+"))
				{
					LOGGER.error("Server sent invalid world key name, and is being ignored.");
					this.isServerCommunicationEnabled = false;
					this.serverIsMalformed = true;
					return;
				}
				
				LOGGER.info("Server sent world change event: " + worldKey);
				MC.executeOnRenderThread(() -> {
					if (MC.getWrappedClientWorld() != null)
					{
						this.clientLevelUnloadEvent((IClientLevelWrapper) MC.getWrappedClientWorld());
					}
					IServerKeyedClientLevel clientLevel = KEYED_CLIENT_LEVEL_MANAGER.getServerKeyedLevel(MC.getWrappedClientWorld(), worldKey);
					KEYED_CLIENT_LEVEL_MANAGER.setServerKeyedLevel(clientLevel);
					this.serverLevelLoadEvent(clientLevel);
				});
				break;
		}
	}



	//===========//
	// rendering //
	//===========//

	public void renderLods(IClientLevelWrapper levelWrapper, Mat4f mcModelViewMatrix, Mat4f mcProjectionMatrix, float partialTicks)
	{
		if (ModInfo.IS_DEV_BUILD && !this.configOverrideReminderPrinted && MC.playerExists())
		{
			// remind the user that this is a development build
			MC.sendChatMessage(ModInfo.READABLE_NAME + " experimental build " + ModInfo.VERSION);
			MC.sendChatMessage("You are running an unsupported version of Distant Horizons!");
			MC.sendChatMessage("Here be dragons!");
			this.configOverrideReminderPrinted = true;
		}


		IProfilerWrapper profiler = MC.getProfiler();
		profiler.pop(); // get out of "terrain"
		profiler.push("DH-RenderLevel");
		try
		{
			if (!RenderUtil.shouldLodsRender(levelWrapper))
			{
				return;
			}


			//FIXME: Improve class hierarchy of DhWorld, IClientWorld, IServerWorld to fix all this hard casting
			// (also in RenderUtil)
			IDhClientWorld dhClientWorld = SharedApi.getIDhClientWorld();
			IDhClientLevel level = dhClientWorld.getOrLoadClientLevel(levelWrapper);

			if (prefLoggerEnabled)
			{
				level.dumpRamUsage();
			}



			profiler.push("Render" + (Config.Client.Advanced.Debugging.rendererMode.get() == ERendererMode.DEFAULT ? "-lods" : "-debug"));
			try
			{
				if (Config.Client.Advanced.Debugging.rendererMode.get() == ERendererMode.DEFAULT)
				{
					DhApiRenderParam renderEventParam =
							new DhApiRenderParam(mcProjectionMatrix, mcModelViewMatrix,
								RenderUtil.createLodProjectionMatrix(mcProjectionMatrix, partialTicks),
								RenderUtil.createLodModelViewMatrix(mcModelViewMatrix), partialTicks);

					boolean renderingCanceled = ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeRenderEvent.class, new DhApiBeforeRenderEvent.EventParam(renderEventParam));
					if (!this.rendererDisabledBecauseOfExceptions && !renderingCanceled)
					{
						level.render(mcModelViewMatrix, mcProjectionMatrix, partialTicks, profiler);
						ApiEventInjector.INSTANCE.fireAllEvents(DhApiAfterRenderEvent.class, new DhApiAfterRenderEvent.EventParam(renderEventParam));
					}
				}
				else if (Config.Client.Advanced.Debugging.rendererMode.get() == ERendererMode.DEBUG)
				{
					ClientApi.testRenderer.render();
				}
				// the other rendererMode is DISABLED
			}
			catch (RuntimeException e)
			{
				this.rendererDisabledBecauseOfExceptions = true;
				LOGGER.error("Renderer thrown an uncaught exception: ", e);

				MC.sendChatMessage("\u00A74\u00A7l\u00A7uERROR: Distant Horizons"
						+ " renderer has encountered an exception!");
				MC.sendChatMessage("\u00A74Renderer is now disabled to prevent further issues.");
				MC.sendChatMessage("\u00A74Exception detail: " + e);
			}
			profiler.pop();
		}
		catch (Exception e)
		{
			LOGGER.error("client level rendering uncaught exception: ", e);
		}
		finally
		{
			profiler.pop(); // end LOD
			profiler.push("terrain"); // go back into "terrain"
		}
	}



	//=================//
	//    DEBUG USE    //
	//=================//

	/** Trigger once on key press, with CLIENT PLAYER. */
	public void keyPressedEvent(int glfwKey)
	{
		if (!Config.Client.Advanced.Debugging.enableDebugKeybindings.get())
		{
			// keybindings are disabled
			return;
		}


		if (glfwKey == GLFW.GLFW_KEY_F8)
		{
			Config.Client.Advanced.Debugging.debugRendering.set(EDebugRendering.next(Config.Client.Advanced.Debugging.debugRendering.get()));
			MC.sendChatMessage("F8: Set debug mode to " + Config.Client.Advanced.Debugging.debugRendering.get());
		}
		else if (glfwKey == GLFW.GLFW_KEY_F6)
		{
			Config.Client.Advanced.Debugging.rendererMode.set(ERendererMode.next(Config.Client.Advanced.Debugging.rendererMode.get()));
			MC.sendChatMessage("F6: Set rendering to " + Config.Client.Advanced.Debugging.rendererMode.get());
		}
		else if (glfwKey == GLFW.GLFW_KEY_P)
		{
			prefLoggerEnabled = !prefLoggerEnabled;
			MC.sendChatMessage("P: Debug Pref Logger is " + (prefLoggerEnabled ? "enabled" : "disabled"));
		}
	}


}
