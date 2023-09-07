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

import com.seibel.distanthorizons.api.enums.config.EGpuUploadMethod;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.glObject.GLState;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.core.render.glObject.shader.ShaderProgram;
import com.seibel.distanthorizons.core.render.glObject.vertexAttribute.VertexAttribute;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.coreapi.util.math.Mat4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class SSAORenderer
{
	public static SSAORenderer INSTANCE = new SSAORenderer();
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	private static final float[] box_vertices = {
			-1, -1,
			1, -1,
			1, 1,
			-1, -1,
			1, 1,
			-1, 1,
	};
	
	
	private ShaderProgram ssaoShader;
	private ShaderProgram applyShader;

	private GLVertexBuffer boxBuffer;
	private VertexAttribute va;
	private boolean init = false;
	
	private int width = -1;
	private int height = -1;
	private int ssaoFramebuffer = -1;
	
	private int ssaoTexture = -1;
	
	// ssao uniforms
	private final SsaoShaderUniforms ssaoShaderUniforms = new SsaoShaderUniforms();
	private static class SsaoShaderUniforms
	{
		public int gProjUniform;
		public int gInvProjUniform;
		public int gSampleCountUniform;
		public int gRadiusUniform;
		public int gStrengthUniform;
		public int gMinLightUniform;
		public int gBiasUniform;
		public int gDepthMapUniform;
	}
	
	// apply uniforms
	private final ApplyShaderUniforms applyShaderUniforms = new ApplyShaderUniforms();
	private static class ApplyShaderUniforms
	{
		public int gSSAOMapUniform;
		public int gDepthMapUniform;
		public int gViewSizeUniform;
		public int gBlurRadiusUniform;
		public int gNearUniform;
		public int gFarUniform;
	}
	
	
	//=============//
	// constructor //
	//=============//
	
	private SSAORenderer() { }
	
	public void init()
	{
		if (this.init) return;
		this.init = true;
		
		this.va = VertexAttribute.create();
		this.va.bind();
		
		// Pos
		this.va.setVertexAttribute(0, 0, VertexAttribute.VertexPointer.addVec2Pointer(false));
		this.va.completeAndCheck(Float.BYTES * 2);
		this.ssaoShader = new ShaderProgram("shaders/normal.vert", "shaders/ssao/ao.frag",
				"fragColor", new String[]{"vPosition"});
		
		this.applyShader = new ShaderProgram("shaders/normal.vert", "shaders/ssao/apply.frag",
				"fragColor", new String[]{"vPosition"});
		
		// SSAO uniform setup
		this.ssaoShaderUniforms.gProjUniform = this.ssaoShader.getUniformLocation("gProj");
		this.ssaoShaderUniforms.gInvProjUniform = this.ssaoShader.getUniformLocation("gInvProj");
		this.ssaoShaderUniforms.gSampleCountUniform = this.ssaoShader.getUniformLocation("gSampleCount");
		this.ssaoShaderUniforms.gRadiusUniform = this.ssaoShader.getUniformLocation("gRadius");
		this.ssaoShaderUniforms.gStrengthUniform = this.ssaoShader.getUniformLocation("gStrength");
		this.ssaoShaderUniforms.gMinLightUniform = this.ssaoShader.getUniformLocation("gMinLight");
		this.ssaoShaderUniforms.gBiasUniform = this.ssaoShader.getUniformLocation("gBias");
		this.ssaoShaderUniforms.gDepthMapUniform = this.ssaoShader.getUniformLocation("gDepthMap");
		
		// Apply uniform setup
		this.applyShaderUniforms.gSSAOMapUniform = this.applyShader.getUniformLocation("gSSAOMap");
		this.applyShaderUniforms.gDepthMapUniform = this.applyShader.getUniformLocation("gDepthMap");
		this.applyShaderUniforms.gViewSizeUniform = tryGetUniformLocation(this.applyShader, "gViewSize");
		this.applyShaderUniforms.gBlurRadiusUniform = tryGetUniformLocation(this.applyShader, "gBlurRadius");
		this.applyShaderUniforms.gNearUniform = tryGetUniformLocation(this.applyShader, "gNear");
		this.applyShaderUniforms.gFarUniform = tryGetUniformLocation(this.applyShader, "gFar");
		
		// Framebuffer
		this.createBuffer();
	}
	
	private void createFramebuffer(int width, int height)
	{
		if (this.ssaoFramebuffer != -1)
		{
			GL32.glDeleteFramebuffers(this.ssaoFramebuffer);
			this.ssaoFramebuffer = -1;
		}
		
		if (this.ssaoTexture != -1)
		{
			GL32.glDeleteTextures(this.ssaoTexture);
			this.ssaoTexture = -1;
		}
		
		this.ssaoFramebuffer = GL32.glGenFramebuffers();
		GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, this.ssaoFramebuffer);
		
		this.ssaoTexture = GL32.glGenTextures();
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, this.ssaoTexture);
		GL32.glTexImage2D(GL32.GL_TEXTURE_2D, 0, GL32.GL_R16F, width, height, 0, GL32.GL_RED, GL32.GL_HALF_FLOAT, (ByteBuffer) null);
		GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_MIN_FILTER, GL32.GL_LINEAR);
		GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_MAG_FILTER, GL32.GL_LINEAR);
		GL32.glFramebufferTexture2D(GL32.GL_FRAMEBUFFER, GL32.GL_COLOR_ATTACHMENT0, GL32.GL_TEXTURE_2D, this.ssaoTexture, 0);
	}
		
	private void createBuffer()
	{
		ByteBuffer buffer = ByteBuffer.allocateDirect(box_vertices.length * Float.BYTES);
		buffer.order(ByteOrder.nativeOrder());
		buffer.asFloatBuffer().put(box_vertices);
		buffer.rewind();
		
		this.boxBuffer = new GLVertexBuffer(false);
		this.boxBuffer.bind();
		this.boxBuffer.uploadBuffer(buffer, box_vertices.length, EGpuUploadMethod.DATA, box_vertices.length * Float.BYTES);
	}
	
	
	//========//
	// render //
	//========//
	
	public void render(float partialTicks)
	{
		GLState state = new GLState();
		
		this.init();

		int width = MC_RENDER.getTargetFrameBufferViewportWidth();
		int height = MC_RENDER.getTargetFrameBufferViewportHeight();
		
		if (this.width != width || this.height != height)
		{
			this.width = width;
			this.height = height;
			this.createFramebuffer(width, height);
		}
		
		GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, this.ssaoFramebuffer);
		GL32.glViewport(0, 0, width, height);
		GL32.glDisable(GL32.GL_SCISSOR_TEST);
		GL32.glDisable(GL32.GL_DEPTH_TEST);
		GL32.glDisable(GL11.GL_BLEND);
		
		float near = RenderUtil.getNearClipPlaneDistanceInBlocks(partialTicks);
		float far = (float) ((RenderUtil.getFarClipPlaneDistanceInBlocks() + LodUtil.REGION_WIDTH) * Math.sqrt(2));
		
		Mat4f perspective = Mat4f.perspective(
				(float) MC_RENDER.getFov(partialTicks),
				width / (float) height,
				near, far);
		
		Mat4f invertedPerspective = new Mat4f(perspective);
		invertedPerspective.invert();
		
		int sampleCount = Config.Client.Advanced.Graphics.Ssao.sampleCount.get();
		int blurRadius = Config.Client.Advanced.Graphics.Ssao.blurRadius.get();
		float radius = Config.Client.Advanced.Graphics.Ssao.radius.get().floatValue();
		float strength = Config.Client.Advanced.Graphics.Ssao.strength.get().floatValue();
		float minLight = Config.Client.Advanced.Graphics.Ssao.minLight.get().floatValue();
		float bias = Config.Client.Advanced.Graphics.Ssao.bias.get().floatValue();
		
		this.ssaoShader.bind();
		this.ssaoShader.setUniform(this.ssaoShaderUniforms.gProjUniform, perspective);
		this.ssaoShader.setUniform(this.ssaoShaderUniforms.gInvProjUniform, invertedPerspective);
		this.ssaoShader.setUniform(this.ssaoShaderUniforms.gSampleCountUniform, sampleCount);
		this.ssaoShader.setUniform(this.ssaoShaderUniforms.gRadiusUniform, radius);
		this.ssaoShader.setUniform(this.ssaoShaderUniforms.gStrengthUniform, strength);
		this.ssaoShader.setUniform(this.ssaoShaderUniforms.gMinLightUniform, minLight);
		this.ssaoShader.setUniform(this.ssaoShaderUniforms.gBiasUniform, bias);
		
		this.va.bind();
		this.va.bindBufferToAllBindingPoint(this.boxBuffer.getId());
		
		GL32.glActiveTexture(GL32.GL_TEXTURE0);
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, MC_RENDER.getDepthTextureId());
		
		GL32.glUniform1i(this.ssaoShaderUniforms.gDepthMapUniform, 0);
		GL32.glDrawArrays(GL32.GL_TRIANGLES, 0, 6);
		
		this.applyShader.bind();
		
		GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, MC_RENDER.getTargetFrameBuffer());
		
		GL32.glEnable(GL11.GL_BLEND);
		GL32.glBlendEquation(GL32.GL_FUNC_ADD);
		GL32.glBlendFuncSeparate(GL32.GL_ZERO, GL32.GL_SRC_ALPHA, GL32.GL_ZERO, GL32.GL_ONE);
		
		GL32.glActiveTexture(GL32.GL_TEXTURE0);
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, this.ssaoTexture);
		GL32.glUniform1i(this.applyShaderUniforms.gSSAOMapUniform, 0);
		GL32.glActiveTexture(GL32.GL_TEXTURE1);
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, MC_RENDER.getDepthTextureId());
		GL32.glUniform1i(this.applyShaderUniforms.gDepthMapUniform, 1);
		GL32.glUniform1i(this.applyShaderUniforms.gBlurRadiusUniform, blurRadius);
		
		if (this.applyShaderUniforms.gViewSizeUniform >= 0)
			GL32.glUniform2f(this.applyShaderUniforms.gViewSizeUniform, width, height);
		
		if (this.applyShaderUniforms.gNearUniform >= 0)
			GL32.glUniform1f(this.applyShaderUniforms.gNearUniform, near);
		
		if (this.applyShaderUniforms.gFarUniform >= 0)
			GL32.glUniform1f(this.applyShaderUniforms.gFarUniform, far);
		
		GL32.glDrawArrays(GL32.GL_TRIANGLES, 0, 6);
		
		state.restore();
	}
	
	private int tryGetUniformLocation(ShaderProgram shader, String uniformName)
	{
		try {
			return shader.getUniformLocation(uniformName);
		}
		catch (RuntimeException error) {
			return -1;
		}
	}
	
	public void free()
	{
		this.ssaoShader.free();
		this.applyShader.free();
	}
}
