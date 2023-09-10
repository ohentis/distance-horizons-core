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

import com.seibel.distanthorizons.api.enums.rendering.EFogColorMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.fog.LodFogConfig;
import com.seibel.distanthorizons.core.render.glObject.shader.Shader;
import com.seibel.distanthorizons.core.render.glObject.shader.ShaderProgram;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.IVersionConstants;
import com.seibel.distanthorizons.coreapi.util.math.Mat4f;
import org.lwjgl.opengl.GL32;

import java.awt.*;

public class FogShader extends AbstractShaderRenderer
{
	public static FogShader INSTANCE = new FogShader(LodFogConfig.generateFogConfig());
	private static final IVersionConstants VERSION_CONSTANTS = SingletonInjector.INSTANCE.get(IVersionConstants.class);
	
	
	public final int gInvertedModelViewProjectionUniform;
	public final int gDepthMapUniform;
	
	// Fog Uniforms
	public final int fogColorUniform;
	public final int fogScaleUniform;
	public final int fogVerticalScaleUniform;
	public final int nearFogStartUniform;
	public final int nearFogLengthUniform;
	public final int fullFogModeUniform;
	
	
	
	public FogShader(LodFogConfig fogConfig)
	{
		super(new ShaderProgram(
				// TODO rename normal.vert to something like "postProcess.vert"
				() -> Shader.loadFile("shaders/normal.vert", false, new StringBuilder()).toString(),
				() -> fogConfig.loadAndProcessFragShader("shaders/fog/fog.frag", false).toString(),
				"fragColor", new String[]{"vPosition"}
		));
				
		// all uniforms should be tryGet...
		// because disabling fog can cause the GLSL to optimize out most (if not all) uniforms
		
		this.gInvertedModelViewProjectionUniform = this.shader.tryGetUniformLocation("gInvMvmProj");
		this.gDepthMapUniform = this.shader.tryGetUniformLocation("gDepthMap");
		
		// Fog uniforms
		this.fogColorUniform = this.shader.tryGetUniformLocation("fogColor");
		this.fullFogModeUniform = this.shader.tryGetUniformLocation("fullFogMode");
		this.fogScaleUniform = this.shader.tryGetUniformLocation("fogScale");
		this.fogVerticalScaleUniform = this.shader.tryGetUniformLocation("fogVerticalScale");
		
		// near fog
		this.nearFogStartUniform = this.shader.tryGetUniformLocation("nearFogStart");
		this.nearFogLengthUniform = this.shader.tryGetUniformLocation("nearFogLength");
	}
	
	@Override
	void setShaderUniforms(float partialTicks)
	{
		this.shader.bind();
		
		int lodDrawDistance = RenderUtil.getFarClipPlaneDistanceInBlocks();
		int vanillaDrawDistance = MC_RENDER.getRenderDistance() * LodUtil.CHUNK_WIDTH;
		vanillaDrawDistance += LodUtil.CHUNK_WIDTH * 2; // Give it a 2 chunk boundary for near fog.
		
		// bind the depth buffer
		if (this.gDepthMapUniform != -1)
		{
			GL32.glActiveTexture(GL32.GL_TEXTURE1);
			GL32.glBindTexture(GL32.GL_TEXTURE_2D, MC_RENDER.getDepthTextureId());
			GL32.glUniform1i(this.gDepthMapUniform, 1);
		}
		
		// Fog
		if (this.fullFogModeUniform != -1) this.shader.setUniform(this.fullFogModeUniform, MC_RENDER.isFogStateSpecial() ? 1 : 0);
		if (this.fogColorUniform != -1) this.shader.setUniform(this.fogColorUniform, MC_RENDER.isFogStateSpecial() ? this.getSpecialFogColor(partialTicks) : this.getFogColor(partialTicks));
		
		float nearFogLen = vanillaDrawDistance * 0.2f / lodDrawDistance;
		float nearFogStart = vanillaDrawDistance * (VERSION_CONSTANTS.isVanillaRenderedChunkSquare() ? (float) Math.sqrt(2.0) : 1.0f) / lodDrawDistance;
		if (this.nearFogStartUniform != -1) this.shader.setUniform(this.nearFogStartUniform, nearFogStart);
		if (this.nearFogLengthUniform != -1) this.shader.setUniform(this.nearFogLengthUniform, nearFogLen);
		if (this.fogScaleUniform != -1) this.shader.setUniform(this.fogScaleUniform, 1.f / lodDrawDistance);
		if (this.fogVerticalScaleUniform != -1) this.shader.setUniform(this.fogVerticalScaleUniform, 1.f / MC.getWrappedClientWorld().getHeight());
	}
	
	private Color getFogColor(float partialTicks)
	{
		Color fogColor;
		
		if (Config.Client.Advanced.Graphics.Fog.colorMode.get() == EFogColorMode.USE_SKY_COLOR)
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
	
	public void setModelViewProjectionMatrix(Mat4f combinedModelViewProjectionMatrix)
	{
		this.shader.bind();
		
		Mat4f inverseMvmProjMatrix = new Mat4f(combinedModelViewProjectionMatrix);
		inverseMvmProjMatrix.invert();
		this.shader.setUniform(this.gInvertedModelViewProjectionUniform, inverseMvmProjMatrix);
		
		this.shader.unbind();
	}
}
