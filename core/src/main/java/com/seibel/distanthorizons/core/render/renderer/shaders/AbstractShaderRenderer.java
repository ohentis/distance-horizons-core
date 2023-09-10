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
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.glObject.GLState;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.core.render.glObject.shader.ShaderProgram;
import com.seibel.distanthorizons.core.render.glObject.vertexAttribute.VertexAttribute;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public abstract class AbstractShaderRenderer
{
	protected static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	protected static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	private static final float[] box_vertices = {
			-1, -1,
			1, -1,
			1, 1,
			-1, -1,
			1, 1,
			-1, 1,
	};
	
	protected final ShaderProgram shader;
	public GLVertexBuffer boxBuffer;
	protected VertexAttribute va;
	boolean init = false;
	
	
	protected AbstractShaderRenderer(ShaderProgram shader)
	{
		this.shader = shader;
	}
	
	private void init()
	{
		if (init) return;
		init = true;
		
		va = VertexAttribute.create();
		va.bind();
		
		// Pos
		setVertexAttributes();
		va.completeAndCheck(Float.BYTES * 2);
		
		// Some shader stuff needs to be set a bit later than
		this.postInit();
		
		// Framebuffer
		this.createBuffer();
	}
	
	/** Sets all the vertex attributes */
	void setVertexAttributes()
	{
		va.setVertexAttribute(0, 0, VertexAttribute.VertexPointer.addVec2Pointer(false));
	}
	
	/** Overwrite this to apply uniforms to the shader */
	void setShaderUniforms(float partialTicks) { }
	
	/** Overwrite if you need to run something on runtime */
	void postInit() { }
	
	
	// TODO pass in the Model View and Projection Matrices along with the ticks
	public void render(float partialTicks)
	{
		GLState state = new GLState();
		this.init();
		
		int width = MC_RENDER.getTargetFrameBufferViewportWidth();
		int height = MC_RENDER.getTargetFrameBufferViewportHeight();
		
		GL32.glViewport(0, 0, width, height);
		GL32.glDisable(GL32.GL_DEPTH_TEST);
		GL32.glDisable(GL32.GL_SCISSOR_TEST);
		
		shader.bind();
		this.setShaderUniforms(partialTicks);
		
		va.bind();
		va.bindBufferToAllBindingPoint(boxBuffer.getId());
		
		GL32.glActiveTexture(GL32.GL_TEXTURE0);
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, MC_RENDER.getDepthTextureId());
		
		GL32.glEnable(GL11.GL_BLEND);
		GL32.glBlendFunc(GL32.GL_SRC_ALPHA, GL32.GL_ONE_MINUS_SRC_ALPHA);
		GL32.glDrawArrays(GL32.GL_TRIANGLES, 0, 6);
		
		state.restore();
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
	
	public void free()
	{
		this.shader.free();
	}
}
