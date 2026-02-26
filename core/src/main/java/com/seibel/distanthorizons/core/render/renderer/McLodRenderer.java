/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
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

package com.seibel.distanthorizons.core.render.renderer;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiRendererMode;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.*;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.DhApiRenderProxy;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.util.math.Vec3d;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.IMcLodRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.IMcTestRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.IVertexBufferWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import com.seibel.distanthorizons.core.util.math.Vec3f;

/**
 * This is where all the magic happens. <br>
 * This is where LODs are draw to the world.
 */
public class McLodRenderer
{
	public static final DhLogger LOGGER = new DhLoggerBuilder()
			.fileLevelConfig(Config.Common.Logging.logRendererEventToFile)
			.build();
	
	public static final DhLogger RATE_LIMITED_LOGGER = new DhLoggerBuilder()
			.fileLevelConfig(Config.Common.Logging.logRendererEventToFile)
			.maxCountPerSecond(4)
			.build();
	
	private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	
	public static final McLodRenderer INSTANCE = new McLodRenderer();
	
	
	
	private boolean renderObjectsCreated = false;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	private McLodRenderer() { }
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	/**
	 * This will draw both opaque and transparent LODs if 
	 * {@link DhApiRenderProxy#getDeferTransparentRendering()} is false,
	 * otherwise it will only render opaque LODs.
	 */
	public void render(RenderParams renderParams, IProfilerWrapper profiler)
	{  this.renderLodPass(renderParams, profiler, false);  }
	
	/**
	 * This method is designed for Iris to be able 
	 * to draw water in a deferred rendering context. 
	 * It needs to be updated with any major changes, 
	 * but shouldn't be activated as per deferWaterRendering.
	 */
	public void renderDeferred(RenderParams renderParams, IProfilerWrapper profiler)
	{ this.renderLodPass(renderParams, profiler, true); }
	
	private void renderLodPass(RenderParams renderParams, IProfilerWrapper profiler, boolean runningDeferredPass)
	{
		//====================//
		// validate rendering //
		//====================//
		
		boolean deferTransparentRendering = DhApiRenderProxy.INSTANCE.getDeferTransparentRendering();
		if (runningDeferredPass 
			&& !deferTransparentRendering)
		{
			return;
		}
		boolean firstPass = !runningDeferredPass;
		
		// RenderParams parameter validation should be done before this
		if (!renderParams.validationRun)
		{
			throw new IllegalArgumentException("Render parameters validation");
		}
		
		RenderBufferHandler renderBufferHandler = renderParams.renderBufferHandler;
		//GenericObjectRenderer genericRenderer = renderParams.genericRenderer;
		
		
		
		//=================//
		// rendering setup //
		//=================//
		
		ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeRenderSetupEvent.class, renderParams);
		profiler.push("LOD GL setup");
		
		if (!this.renderObjectsCreated)
		{
			// only do this once, that way they can still be reverted if desired
			if (Config.Client.Advanced.Graphics.overrideVanillaGraphicsSettings.get())
			{
				LOGGER.info("Overriding vanilla MC settings to better fit Distant Horizons... This behavior can be disabled in the Distant Horizons config.");
				
				MC.disableVanillaClouds();
				MC.disableVanillaChunkFadeIn();
				MC.disableFabulousTransparency();
			}
			
			this.renderObjectsCreated = true;
		}
		
		if (firstPass)
		{
			// we only need to sort/cull the LODs during the first frame 
			profiler.popPush("LOD build render list");
			renderBufferHandler.buildRenderList(renderParams);
		}
		
		IMcLodRenderer lodRenderer = SingletonInjector.INSTANCE.get(IMcLodRenderer.class);
		
		
		
		//===========//
		// rendering //
		//===========//
		
		if (!runningDeferredPass)
		{
			//=========================//
			// opaque and non-deferred //
			// transparent rendering   //
			//=========================//
			
			// opaque LODs
			profiler.popPush("LOD Opaque");
			
			this.renderLodPass(lodRenderer, renderBufferHandler, renderParams, /*opaquePass*/ true, profiler);
			
			// custom objects with SSAO
			if (Config.Client.Advanced.Graphics.GenericRendering.enableGenericRendering.get())
			{
				//profiler.popPush("Custom Objects");
				//genericRenderer.render(renderParams, profiler, true);
			}
			
			// SSAO
			if (Config.Client.Advanced.Graphics.Ssao.enableSsao.get())
			{
				//profiler.popPush("LOD SSAO");
				//SSAORenderer.INSTANCE.render(new Mat4f(renderParams.dhProjectionMatrix), renderParams.partialTicks);
			}
			
			// custom objects without SSAO
			if (Config.Client.Advanced.Graphics.GenericRendering.enableGenericRendering.get())
			{
				//profiler.popPush("Custom Objects");
				//genericRenderer.render(renderParams, profiler, false);
			}
			
			// combined pass transparent rendering
			if (!deferTransparentRendering 
				&& Config.Client.Advanced.Graphics.Quality.transparency.get().transparencyEnabled)
			{
				profiler.popPush("LOD Transparent");
				this.renderLodPass(lodRenderer, renderBufferHandler, renderParams, /*opaquePass*/ false, profiler);
			}
			
			// far plane clip fading
			if (Config.Client.Advanced.Graphics.Quality.dhFadeFarClipPlane.get())
			{
				//profiler.popPush("Fade Far Clip Fade");
				//DhFadeRenderer.INSTANCE.render(
				//		new Mat4f(renderParams.mcModelViewMatrix), new Mat4f(renderParams.mcProjectionMatrix),
				//		renderParams.partialTicks, profiler);
			}
			
			// fog
			if (Config.Client.Advanced.Graphics.Fog.enableDhFog.get() 
				// this is done to fix issues with: underwater fog, blindness effect, etc.
				|| renderParams.vanillaFogEnabled)
			{
				//profiler.popPush("LOD Fog");
				//
				//Mat4f combinedMatrix = new Mat4f(renderParams.dhProjectionMatrix);
				//combinedMatrix.multiply(renderParams.dhModelViewMatrix);
				//
				//FogRenderer.INSTANCE.render(combinedMatrix, renderParams.partialTicks);
			}
			
			
			
			//=================//
			// debug rendering //
			//=================//
			
			if (Config.Client.Advanced.Debugging.DebugWireframe.enableRendering.get())
			{
				//profiler.popPush("Debug wireframes");
				//
				//Mat4f combinedMatrix = new Mat4f(renderParams.dhProjectionMatrix);
				//combinedMatrix.multiply(renderParams.dhModelViewMatrix);
				//
				//// Note: this can be very slow if a lot of boxes are being rendered 
				//DebugRenderer.INSTANCE.render(combinedMatrix);
			}
			
			lodRenderer.clearDepth();
			
		}
		else
		{
			////====================//
			//// deferred rendering //
			////====================//
			//
			//if (Config.Client.Advanced.Graphics.Quality.transparency.get().transparencyEnabled)
			//{
			//	profiler.popPush("LOD Transparent");
			//	this.renderLodPass(lodShaderProgram, renderBufferHandler, renderParams, /*opaquePass*/ false);
			//	
			//	
			//	if (Config.Client.Advanced.Graphics.Fog.enableDhFog.get()
			//		// this is done to fix issues with: underwater fog, blindness effect, etc.
			//		|| renderParams.vanillaFogEnabled)
			//	{
			//		profiler.popPush("LOD Fog");
			//		
			//		Mat4f combinedMatrix = new Mat4f(renderParams.dhProjectionMatrix);
			//		combinedMatrix.multiply(renderParams.dhModelViewMatrix);
			//		
			//		FogRenderer.INSTANCE.render(combinedMatrix, renderParams.partialTicks);
			//	}
			//}
		}
		
		
		
		//================//
		// render cleanup //
		//================//
		
		profiler.popPush("LOD cleanup");
		ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeRenderCleanupEvent.class, renderParams);
		
		
		
		// end of internal LOD profiling
		profiler.pop();
	}
	
	//endregion
	
	
	
	
	
	//===============//
	// LOD rendering //
	//===============//
	//region
	
	private void renderLodPass(IMcLodRenderer lodRenderer, RenderBufferHandler lodBufferHandler, RenderParams renderEventParam, boolean opaquePass, IProfilerWrapper profilerWrapper)
	{
		//===========//
		// rendering //
		//===========//
		
		ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeRenderPassEvent.class, renderEventParam);
		
		if (Config.Client.Advanced.Debugging.rendererMode.get() == EDhApiRendererMode.DEFAULT)
		{
			// Normal LOD rendering
			
			SortedArraySet<LodBufferContainer> lodBufferContainer = lodBufferHandler.getColumnRenderBuffers();
			if (lodBufferContainer != null)
			{
				for (int lodIndex = 0; lodIndex < lodBufferContainer.size(); lodIndex++)
				{
					LodBufferContainer bufferContainer = lodBufferContainer.get(lodIndex);
					// TODO match buffer builder debugger
					//if (bufferContainer.pos != DhSectionPos.encode((byte)6, 1,0))
					//{
					//	continue;
					//}
					
					Vec3d camPos = renderEventParam.exactCameraPosition;
					Vec3f modelPos = new Vec3f(
						(float) (bufferContainer.minCornerBlockPos.getX() - camPos.x),
						(float) (bufferContainer.minCornerBlockPos.getY() - camPos.y),
						(float) (bufferContainer.minCornerBlockPos.getZ() - camPos.z));
					
					ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeBufferRenderEvent.class, new DhApiBeforeBufferRenderEvent.EventParam(renderEventParam, modelPos));

					IVertexBufferWrapper[] vbos = opaquePass ? bufferContainer.vbos : bufferContainer.vbosTransparent;
					lodRenderer.render(renderEventParam, opaquePass, modelPos, vbos, profilerWrapper);
				}
			}
		}
		else
		{
			IMcTestRenderer testRenderer = SingletonInjector.INSTANCE.get(IMcTestRenderer.class);
			testRenderer.render();
		}
	}
	
	//endregion
	
	
	
	//===============//
	// API functions //
	//===============//
	//region
	
	/** @return -1 if no frame buffer has been bound yet */
	public int getActiveFramebufferId() { return -1; }
	
	/** @return -1 if no texture has been bound yet */
	public int getActiveColorTextureId() { return -1; }
	
	/** @return -1 if no texture has been bound yet */
	public int getActiveDepthTextureId() { return -1; }
	
	//endregion
	
	
	
}
