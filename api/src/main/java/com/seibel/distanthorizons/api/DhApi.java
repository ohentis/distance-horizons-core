package com.seibel.distanthorizons.api;

import com.seibel.distanthorizons.api.interfaces.events.IDhApiEventInjector;
import com.seibel.distanthorizons.api.interfaces.override.IDhApiOverrideable;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGeneratorOverrideRegister;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.override.DhApiWorldGeneratorOverrideRegister;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IOverrideInjector;

/**
 * This is the masthead of the API, almost everything you could want to do
 * can be achieved from here. <br>
 * For example: you can access singletons which handle the config or event binding. <br><br>
 *
 * <strong>Q:</strong> Why should I use this class instead of just getting the API singleton I need? <br>
 *
 * <strong>A:</strong> This way there is a lower chance of your code breaking if we change something on our end.
 * For example, if we realized there is a much better way of handling dependency injection we would keep the
 * interface the same so your code doesn't have to change. Whereas if you were directly referencing
 * the concrete object we replaced, there would be issues.
 *
 * @author James Seibel
 * @version 2023-6-29
 */
public class DhApi
{
	/**
	 * <strong>WARNING:</strong>
	 * All objects in this class will be null until after DH initializes for the first time. <br><br>
	 *
	 * Bind a custom {@link DhApiAfterDhInitEvent DhApiAfterDhInitEvent}
	 * to {@link DhApi#events ApiCoreInjectors.events} in order to be notified when this class can
	 * be safely used.
	 */
	public static class Delayed
	{
		/** Used to interact with Distant Horizons' Configs. */
		public static IDhApiConfig configs = null;
		
		/**
		 * Used to interact with Distant Horizons' terrain data.
		 * Designed to be used in conjunction with {@link DhApi.Delayed#worldProxy}.
		 */
		public static IDhApiTerrainDataRepo terrainRepo = null;
		
		/**
		 * Used to interact with Distant Horizons' currently loaded world.
		 * Designed to be used in conjunction with {@link DhApi.Delayed#terrainRepo}.
		 */
		public static IDhApiWorldProxy worldProxy = null;
		
		/** Used to interact with Distant Horizons' rendering system. */
		public static IDhApiRenderProxy renderProxy = null;
		
	}
	
	
	// always available //
	
	/** Used to bind/unbind Distant Horizons Api events. */
	public static final IDhApiEventInjector events = ApiEventInjector.INSTANCE;
	
	/** Used to bind/unbind Distant Horizons Api events. */
	public static final IDhApiWorldGeneratorOverrideRegister worldGenOverrides = DhApiWorldGeneratorOverrideRegister.INSTANCE;
	
	/** Used to bind overrides to change Distant Horizons' core behavior. */
	public static final IOverrideInjector<IDhApiOverrideable> overrides = OverrideInjector.INSTANCE;
	
	
	/** This version should only be updated when breaking changes are introduced to the Distant Horizons API. */
	public static int getApiMajorVersion() { return ModInfo.API_MAJOR_VERSION; }
	/** This version should be updated whenever new methods are added to the Distant Horizons API. */
	public static int getApiMinorVersion() { return ModInfo.API_MINOR_VERSION; }
	/** This version should be updated whenever non-breaking fixes are added to the Distant Horizons API. */
	public static int getApiPatchVersion() { return ModInfo.API_PATH_VERSION; }
	
	/**
	 * Returns the mod's semantic version number in the format: Major.Minor.Patch
	 * with optional extensions "-a" for alpha, "-b" for beta, and -dev for unstable development builds. <br>
	 * Examples: "1.6.9-a", "1.7.0-a-dev", "2.1.0-b", "3.0.0", "3.1.4-dev"
	 */
	public static String getModVersion() { return ModInfo.VERSION; }
	/** Returns true if the mod is a development version, false if it is a release version. */
	public static boolean getIsDevVersion() { return ModInfo.IS_DEV_BUILD; }
	
	/** Returns the network protocol version. */
	public static int getNetworkProtocolVersion() { return ModInfo.PROTOCOL_VERSION; }
	
}
