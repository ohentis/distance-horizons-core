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

import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.glObject.GLState;
import com.seibel.distanthorizons.core.render.glObject.shader.ShaderProgram;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
import com.seibel.distanthorizons.core.render.renderer.ScreenQuad;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.math.Mat4f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import org.lwjgl.opengl.GL32;

public class FadeShader extends AbstractShaderRenderer
{
	public static FadeShader INSTANCE = new FadeShader();
	
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	
	public int frameBuffer = -1;
	
	private Mat4f inverseMvmProjMatrix;
	
	
	// Uniforms
	public int uMcDepthTexture = -1;
	public int uCombinedMcDhColorTexture = -1;
	public int uDhColorTexture = -1;
	
	/** Inverted Model View Projection matrix */
	public int uInvMvmProj = -1;
	
	public int uStartFadeBlockDistance = -1;
	public int uEndFadeBlockDistance = -1;
	
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public FadeShader() {  }

	@Override
	public void onInit()
	{
		this.shader = new ShaderProgram(
				"shaders/normal.vert", "shaders/fade/fade.frag",
				"fragColor", new String[]{"vPosition"}
		);
		
		// all uniforms should be tryGet...
		// because disabling fade can cause the GLSL to optimize out most (if not all) uniforms
		
		// near fade
		this.uInvMvmProj = this.shader.tryGetUniformLocation("uInvMvmProj");
		
		this.uMcDepthTexture = this.shader.tryGetUniformLocation("uMcDepthMap");
		this.uCombinedMcDhColorTexture = this.shader.tryGetUniformLocation("uCombinedMcDhColorTexture");
		this.uDhColorTexture = this.shader.tryGetUniformLocation("uDhColorTexture");
		
		this.uStartFadeBlockDistance = this.shader.tryGetUniformLocation("uStartFadeBlockDistance");
		this.uEndFadeBlockDistance = this.shader.tryGetUniformLocation("uEndFadeBlockDistance");
		
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
		
		
		int vanillaBlockRenderDistance = MC_RENDER.getRenderDistance() * LodUtil.CHUNK_WIDTH;
		// measured in blocks
		float fadeStartDistance = vanillaBlockRenderDistance * 0.5f;
		float fadeEndDistance = vanillaBlockRenderDistance * 0.8f;
		
		if (this.uStartFadeBlockDistance != -1) this.shader.setUniform(this.uStartFadeBlockDistance, fadeStartDistance);
		if (this.uEndFadeBlockDistance != -1) this.shader.setUniform(this.uEndFadeBlockDistance, fadeEndDistance);
	}
	
	public void setProjectionMatrix(Mat4f mcModelViewMatrix, Mat4f mcProjectionMatrix)
	{
		Mat4f inverseModelViewProjectionMatrix = new Mat4f(mcProjectionMatrix);
		inverseModelViewProjectionMatrix.multiply(mcModelViewMatrix);
		inverseModelViewProjectionMatrix.invert();
		
		this.inverseMvmProjMatrix = inverseModelViewProjectionMatrix; 
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
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, MC_RENDER.getDepthTextureId());
		GL32.glUniform1i(this.uMcDepthTexture, 0);
		
		GL32.glActiveTexture(GL32.GL_TEXTURE1);
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, MC_RENDER.getColorTextureId());
		GL32.glUniform1i(this.uCombinedMcDhColorTexture, 1);
		
		GL32.glActiveTexture(GL32.GL_TEXTURE2);
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, LodRenderer.getActiveColorTextureId());
		GL32.glUniform1i(this.uDhColorTexture, 2);
		
		// this is necessary for MC 1.16 (IE Legacy OpenGL)
		// otherwise the framebuffer isn't cleared correctly and the fade smears across the screen
		GL32.glClear(GL32.GL_COLOR_BUFFER_BIT | GL32.GL_DEPTH_BUFFER_BIT);
		
		
		ScreenQuad.INSTANCE.render();
		
		state.restore();
	}
	
}
