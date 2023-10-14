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

import com.seibel.distanthorizons.core.render.glObject.shader.ShaderProgram;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
import com.seibel.distanthorizons.core.render.renderer.SSAORenderer;
import com.seibel.distanthorizons.core.render.renderer.ScreenQuad;
import org.lwjgl.opengl.GL32;

/**
 * Copies {@link LodRenderer}'s currently active color and depth texture to Minecraft's framebuffer. 
 */
public class DhApplyShader extends AbstractShaderRenderer
{
	public static DhApplyShader INSTANCE = new DhApplyShader();
	
	// uniforms
	public int gDhColorTextureUniform;
	public int gDepthMapUniform;
	
	
	@Override
	public void onInit()
	{
		this.shader = new ShaderProgram(
				"shaders/normal.vert",
				"shaders/apply.frag",
				"fragColor",
				new String[]{"vPosition"});
		
		// uniform setup
		this.gDhColorTextureUniform = this.shader.getUniformLocation("gDhColorTexture");
		this.gDepthMapUniform = this.shader.getUniformLocation("gDhDepthTexture");
	}
	
	@Override
	protected void onApplyUniforms(float partialTicks)
	{
		GL32.glActiveTexture(GL32.GL_TEXTURE0);
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, LodRenderer.getActiveColorTextureId());
		GL32.glUniform1i(this.gDhColorTextureUniform, 0);
		
		GL32.glActiveTexture(GL32.GL_TEXTURE1);
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, LodRenderer.getActiveDepthTextureId());
		GL32.glUniform1i(this.gDepthMapUniform, 1);
		
	}
	
	
	//========//
	// render //
	//========//
	
	@Override
	protected void onRender()
	{
		GL32.glDisable(GL32.GL_DEPTH_TEST);
		
		GL32.glEnable(GL32.GL_BLEND);
		GL32.glBlendEquation(GL32.GL_FUNC_ADD);
		GL32.glBlendFunc(GL32.GL_ONE, GL32.GL_ONE_MINUS_SRC_ALPHA);

		// Copy to MC's framebuffer
		GL32.glBindFramebuffer(GL32.GL_READ_FRAMEBUFFER, 0); // this framebuffer shouldn't be used since we are reading in from a texture instead
		GL32.glBindFramebuffer(GL32.GL_DRAW_FRAMEBUFFER, MC_RENDER.getTargetFrameBuffer());
		
		ScreenQuad.INSTANCE.render();
	}
}
