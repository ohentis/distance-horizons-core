/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2023 James Seibel
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

package com.seibel.distanthorizons.core.render.renderer.shaders;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogColorMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.fog.LodFogConfig;
import com.seibel.distanthorizons.core.render.glObject.GLState;
import com.seibel.distanthorizons.core.render.glObject.shader.Shader;
import com.seibel.distanthorizons.core.render.glObject.shader.ShaderProgram;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
import com.seibel.distanthorizons.core.render.renderer.ScreenQuad;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.IVersionConstants;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.util.math.Mat4f;
import org.lwjgl.opengl.GL32;

import java.awt.*;

public class FogShader extends AbstractShaderRenderer
{
	public static FogShader INSTANCE = new FogShader(LodFogConfig.generateFogConfig());
	
	private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IVersionConstants VERSION_CONSTANTS = SingletonInjector.INSTANCE.get(IVersionConstants.class);
	
	
	public int frameBuffer;
	
	private final LodFogConfig fogConfig;
	private Mat4f inverseMvmProjMatrix;
	
	
	// Uniforms
	public int uFogColor;
	public int uFogScale;
	public int uFogVerticalScale;
	public int uNearFogStart;
	public int uNearFogLength;
	public int uFullFogMode;
	
	/** Inverted Model View Projection matrix */
	public int uInvMvmProj;
	public int uDepthMap;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public FogShader(LodFogConfig fogConfig) { this.fogConfig = fogConfig; }

	@Override
	public void onInit()
	{
		this.shader = new ShaderProgram(
				// TODO rename normal.vert to something like "postProcess.vert"
				() -> Shader.loadFile("shaders/normal.vert", false, new StringBuilder()).toString(),
				() -> this.fogConfig.loadAndProcessFragShader("shaders/fog/fog.frag", false).toString(),
				"fragColor", new String[]{"vPosition"}
		);
		
		// all uniforms should be tryGet...
		// because disabling fog can cause the GLSL to optimize out most (if not all) uniforms
		
		this.uDepthMap = this.shader.getUniformLocation("uDepthMap");
		this.uInvMvmProj = this.shader.getUniformLocation("uInvMvmProj");
		
		// Fog uniforms
		this.uFogScale = this.shader.tryGetUniformLocation("uFogScale");
		this.uFogVerticalScale = this.shader.tryGetUniformLocation("uFogVerticalScale");
		this.uFogColor = this.shader.tryGetUniformLocation("uFogColor");
		this.uFullFogMode = this.shader.tryGetUniformLocation("uFullFogMode");
		
		// near fog
		this.uNearFogStart = this.shader.tryGetUniformLocation("uNearFogStart");
		this.uNearFogLength = this.shader.tryGetUniformLocation("uNearFogLength");
		
	}
	
	
	
	//=============//
	// render prep //
	//=============//
	
	@Override
	protected void onApplyUniforms(float partialTicks)
	{
		if (this.inverseMvmProjMatrix != null)
		{
			this.shader.setUniform(this.uInvMvmProj, this.inverseMvmProjMatrix);
		}
		
		int lodDrawDistance = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get() * LodUtil.CHUNK_WIDTH;
		
		// Fog
		if (this.uFullFogMode != -1) this.shader.setUniform(this.uFullFogMode, MC_RENDER.isFogStateSpecial() ? 1 : 0);
		if (this.uFogColor != -1) this.shader.setUniform(this.uFogColor, MC_RENDER.isFogStateSpecial() ? this.getSpecialFogColor(partialTicks) : this.getFogColor(partialTicks));
		
		float nearFogStart = (VERSION_CONSTANTS.isVanillaRenderedChunkSquare() ? (float) Math.sqrt(2.0) : 1.0f) / lodDrawDistance;
		if (this.uNearFogStart != -1) this.shader.setUniform(this.uNearFogStart, nearFogStart);
		if (this.uNearFogLength != -1) this.shader.setUniform(this.uNearFogLength, 0.0f);
		if (this.uFogScale != -1) this.shader.setUniform(this.uFogScale, 1.f / lodDrawDistance);
		if (this.uFogVerticalScale != -1) this.shader.setUniform(this.uFogVerticalScale, 1.f / MC.getWrappedClientLevel().getMaxHeight());
	}
	private Color getFogColor(float partialTicks)
	{
		Color fogColor;
		
		if (Config.Client.Advanced.Graphics.Fog.colorMode.get() == EDhApiFogColorMode.USE_SKY_COLOR)
		{
			fogColor = MC_RENDER.getSkyColor();
		}
		else
		{
			fogColor = MC_RENDER.getFogColor(partialTicks);
		}
		
		return fogColor;
	}
	private Color getSpecialFogColor(float partialTicks) { return MC_RENDER.getSpecialFogColor(partialTicks); }
	
	public void setProjectionMatrix(Mat4f projectionMatrix)
	{
		this.inverseMvmProjMatrix = new Mat4f(projectionMatrix);
		this.inverseMvmProjMatrix.invert();
	}
	
	
	
	//========//
	// render //
	//========//
	
	@Override
	protected void onRender()
	{
		GLState state = new GLState();
		
		GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, this.frameBuffer);
		GL32.glDisable(GL32.GL_SCISSOR_TEST);
		GL32.glDisable(GL32.GL_DEPTH_TEST);
		GL32.glDisable(GL32.GL_BLEND);
		
		GL32.glActiveTexture(GL32.GL_TEXTURE0);
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, LodRenderer.getActiveDepthTextureId());
		GL32.glUniform1i(this.uDepthMap, 0);
		
		// this is necessary for MC 1.16 (IE Legacy OpenGL)
		// otherwise the framebuffer isn't cleared correctly and the fog smears across the screen
		GL32.glClear(GL32.GL_COLOR_BUFFER_BIT | GL32.GL_DEPTH_BUFFER_BIT);
		
		
		ScreenQuad.INSTANCE.render();
		
		state.restore();
	}
	
}
