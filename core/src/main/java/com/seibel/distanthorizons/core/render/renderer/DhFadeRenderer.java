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

import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.glObject.GLState;
import com.seibel.distanthorizons.core.render.renderer.shaders.DhFadeApplyShader;
import com.seibel.distanthorizons.core.render.renderer.shaders.DhFadeShader;
import com.seibel.distanthorizons.core.util.math.Mat4f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftGLWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.lwjgl.opengl.GL32;

import java.nio.ByteBuffer;

/**
 * Handles fading MC and DH together via {@link DhFadeShader} and {@link DhFadeApplyShader}. <br><br>
 * 
 * {@link DhFadeShader} - draws the Fade to a texture. <br>
 * {@link DhFadeApplyShader} - draws the Fade texture to MC's FrameBuffer. <br>
 */
public class DhFadeRenderer
{
	
	public static DhFadeRenderer INSTANCE = new DhFadeRenderer();
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final IMinecraftGLWrapper GLMC = SingletonInjector.INSTANCE.get(IMinecraftGLWrapper.class);
	
	
	private boolean init = false;
	
	private int width = -1;
	private int height = -1;
	private int fadeFramebuffer = -1;
	
	private int fadeTexture = -1;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	private DhFadeRenderer() { }
	
	public void init()
	{
		if (this.init) return;
		this.init = true;
		
		DhFadeShader.INSTANCE.init();
		DhFadeApplyShader.INSTANCE.init();
	}
	
	private void createFramebuffer(int width, int height)
	{
		if (this.fadeFramebuffer != -1)
		{
			GL32.glDeleteFramebuffers(this.fadeFramebuffer);
			this.fadeFramebuffer = -1;
		}
		
		this.fadeFramebuffer = GL32.glGenFramebuffers();
		GLMC.glBindFramebuffer(GL32.GL_FRAMEBUFFER, this.fadeFramebuffer);
		
		
		if (this.fadeTexture != -1)
		{
			GLMC.glDeleteTextures(this.fadeTexture);
			this.fadeTexture = -1;
		}
		
		this.fadeTexture = GL32.glGenTextures();
		GLMC.glBindTexture(this.fadeTexture);
		GL32.glTexImage2D(GL32.GL_TEXTURE_2D, 0, GL32.GL_RGBA16, width, height, 0, GL32.GL_RGBA, GL32.GL_UNSIGNED_SHORT_4_4_4_4, (ByteBuffer) null);
		GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_MIN_FILTER, GL32.GL_LINEAR);
		GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_MAG_FILTER, GL32.GL_LINEAR);
		GL32.glFramebufferTexture2D(GL32.GL_FRAMEBUFFER, GL32.GL_COLOR_ATTACHMENT0, GL32.GL_TEXTURE_2D, this.fadeTexture, 0);
	}
	
	
	
	//========//
	// render //
	//========//
	
	public void render(Mat4f mcModelViewMatrix, Mat4f mcProjectionMatrix, float partialTicks, IProfilerWrapper profiler)
	{
		GLState mcState = new GLState();
		
		try
		{
			profiler.push("Fade Generate");
			
			this.init();
			
			// resize the framebuffer if necessary
			int width = MC_RENDER.getTargetFramebufferViewportWidth();
			int height = MC_RENDER.getTargetFramebufferViewportHeight();
			if (this.width != width || this.height != height)
			{
				this.width = width;
				this.height = height;
				this.createFramebuffer(width, height);
			}
			
			
			DhFadeShader.INSTANCE.frameBuffer = this.fadeFramebuffer;
			DhFadeShader.INSTANCE.setProjectionMatrix(mcModelViewMatrix, mcProjectionMatrix, partialTicks);
			DhFadeShader.INSTANCE.render(partialTicks);
			
			// restored so we can write the fade texture to the main frame buffer
			//mcState.restore();
			
			profiler.popPush("Fade Apply");
			
			DhFadeApplyShader.INSTANCE.fadeTexture = this.fadeTexture;
			DhFadeApplyShader.INSTANCE.render(partialTicks);
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected error during fade render, error: ["+e.getMessage()+"].", e);
		}
		finally
		{
			// make sure we always revert to MC's state to prevent GL state corruption
			// this is especially important on MC 1.16.5 or when other rendering mods are present
			mcState.restore();
			
			profiler.pop();
		}
	}
	
	
	
}
