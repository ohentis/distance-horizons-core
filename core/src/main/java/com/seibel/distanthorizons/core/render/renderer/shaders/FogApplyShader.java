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
import com.seibel.distanthorizons.core.render.renderer.FogRenderer;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
import com.seibel.distanthorizons.core.render.renderer.ScreenQuad;
import org.lwjgl.opengl.GL32;

/**
 * Draws the Fog texture onto DH's FrameBuffer. <br><br>
 * 
 * See Also: <br>
 * {@link FogRenderer} - Parent to this shader. <br>
 * {@link FogShader} - draws the Fog texture. <br>
 */
public class FogApplyShader extends AbstractShaderRenderer
{
	public static FogApplyShader INSTANCE = new FogApplyShader();
	
	public int fogTexture;
	
	// uniforms
	public int colorTextureUniform;
	public int depthTextureUniform;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	@Override
	public void onInit()
	{
		this.shader = new ShaderProgram(
				"shaders/normal.vert",
				"shaders/fog/apply.frag",
				"fragColor",
				new String[]{ "vPosition" });
		
		// uniform setup
		this.colorTextureUniform = this.shader.getUniformLocation("uColorTexture");
		this.depthTextureUniform = this.shader.getUniformLocation("uDepthTexture");
		
	}
	
	
	
	//=============//
	// render prep //
	//=============//
	
	@Override
	protected void onApplyUniforms(float partialTicks)
	{
		GL32.glActiveTexture(GL32.GL_TEXTURE0);
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, this.fogTexture);
		GL32.glUniform1i(this.colorTextureUniform, 0);
		
		GL32.glActiveTexture(GL32.GL_TEXTURE1);
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, LodRenderer.getActiveDepthTextureId());
		GL32.glUniform1i(this.depthTextureUniform, 1);
	}
	
	
	
	//========//
	// render //
	//========//
	
	@Override
	protected void onRender()
	{
		GL32.glEnable(GL32.GL_BLEND);
		GL32.glBlendEquation(GL32.GL_FUNC_ADD);
		GL32.glBlendFuncSeparate(GL32.GL_SRC_ALPHA, GL32.GL_ONE_MINUS_SRC_ALPHA, GL32.GL_ONE, GL32.GL_ONE_MINUS_SRC_ALPHA);
		
		// Depth testing must be disabled otherwise this application shader won't apply anything.
		// setting this isn't necessary in vanilla, but some mods may change this, requiring it to be set manually, 
		// it should be automatically restored after rendering is complete.
		GL32.glDisable(GL32.GL_DEPTH_TEST);
		
		
		// apply the rendered Fog to DH's framebuffer
		GL32.glBindFramebuffer(GL32.GL_READ_FRAMEBUFFER, FogShader.INSTANCE.frameBuffer);
		GL32.glBindFramebuffer(GL32.GL_DRAW_FRAMEBUFFER, LodRenderer.getActiveFramebufferId());
		
		ScreenQuad.INSTANCE.render();
	}
	
}
