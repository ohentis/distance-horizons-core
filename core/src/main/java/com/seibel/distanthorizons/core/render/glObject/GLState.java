/*
 *    This file is part of the Distant Horizons mod (formerly the LOD Mod),
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2022  James Seibel
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

package com.seibel.distanthorizons.core.render.glObject;

import org.lwjgl.opengl.GL32;

public class GLState
{
	
	public int prog;
	public int vao;
	public int vbo;
	public int ebo;
	public int fbo;
	public int text;
	public int activeTex;
	public int text0;
	public boolean blend;
	public int blendSrc;
	public int blendDst;
	public boolean depth;
	public boolean depthWrite;
	public int depthFunc;
	public boolean stencil;
	public int stencilFunc;
	public int stencilRef;
	public int stencilMask;
	public int[] view;
	public boolean cull;
	public int cullMode;
	public int polyMode;
	
	
	
	public GLState() { this.saveState(); }
	public void saveState()
	{
		this.prog = GL32.glGetInteger(GL32.GL_CURRENT_PROGRAM);
		this.vao = GL32.glGetInteger(GL32.GL_VERTEX_ARRAY_BINDING);
		this.vbo = GL32.glGetInteger(GL32.GL_ARRAY_BUFFER_BINDING);
		this.ebo = GL32.glGetInteger(GL32.GL_ELEMENT_ARRAY_BUFFER_BINDING);
		this.fbo = GL32.glGetInteger(GL32.GL_FRAMEBUFFER_BINDING);
		this.text = GL32.glGetInteger(GL32.GL_TEXTURE_BINDING_2D);
		this.activeTex = GL32.glGetInteger(GL32.GL_ACTIVE_TEXTURE);
		GL32.glActiveTexture(GL32.GL_TEXTURE0);
		this.text0 = GL32.glGetInteger(GL32.GL_TEXTURE_BINDING_2D);
		GL32.glActiveTexture(this.activeTex);
		this.blend = GL32.glIsEnabled(GL32.GL_BLEND);
		this.blendSrc = GL32.glGetInteger(GL32.GL_BLEND_SRC);
		this.blendDst = GL32.glGetInteger(GL32.GL_BLEND_DST);
		this.depth = GL32.glIsEnabled(GL32.GL_DEPTH_TEST);
		this.depthWrite = GL32.glGetInteger(GL32.GL_DEPTH_WRITEMASK) == GL32.GL_TRUE;
		this.depthFunc = GL32.glGetInteger(GL32.GL_DEPTH_FUNC);
		this.stencil = GL32.glIsEnabled(GL32.GL_STENCIL_TEST);
		this.stencilFunc = GL32.glGetInteger(GL32.GL_STENCIL_FUNC);
		this.stencilRef = GL32.glGetInteger(GL32.GL_STENCIL_REF);
		this.stencilMask = GL32.glGetInteger(GL32.GL_STENCIL_VALUE_MASK);
		this.view = new int[4];
		GL32.glGetIntegerv(GL32.GL_VIEWPORT, this.view);
		this.cull = GL32.glIsEnabled(GL32.GL_CULL_FACE);
		this.cullMode = GL32.glGetInteger(GL32.GL_CULL_FACE_MODE);
		this.polyMode = GL32.glGetInteger(GL32.GL_POLYGON_MODE);
	}
	
	@Override
	public String toString()
	{
		return "GLState{" +
				"prog=" + this.prog + ", vao=" + this.vao + ", vbo=" + this.vbo + ", ebo=" + this.ebo + ", fbo=" + this.fbo +
				", text=" + GLEnums.getString(this.text) + "@" + this.activeTex + ", text0=" + GLEnums.getString(this.text0) +
				", blend=" + this.blend + ", blendMode=" + GLEnums.getString(this.blendSrc) + "," + GLEnums.getString(this.blendDst) +
				", depth=" + this.depth +
				", depthFunc=" + GLEnums.getString(this.depthFunc) + ", stencil=" + this.stencil + ", stencilFunc=" +
				GLEnums.getString(this.stencilFunc) + ", stencilRef=" + this.stencilRef + ", stencilMask=" + this.stencilMask +
				", view={x:" + this.view[0] + ", y:" + this.view[1] +
				", w:" + this.view[2] + ", h:" + this.view[3] + "}" + ", cull=" + this.cull + ", cullMode="
				+ GLEnums.getString(this.cullMode) + ", polyMode=" + GLEnums.getString(this.polyMode) +
				'}';
	}
	
	public void restore()
	{
		GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, this.fbo);
		if (this.blend)
		{
			GL32.glEnable(GL32.GL_BLEND);
		}
		else
		{
			GL32.glDisable(GL32.GL_BLEND);
		}
		
		GL32.glActiveTexture(GL32.GL_TEXTURE0);
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, this.text0);
		GL32.glActiveTexture(this.activeTex);
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, this.text);
		GL32.glBindVertexArray(this.vao);
		GL32.glBindBuffer(GL32.GL_ARRAY_BUFFER, this.vbo);
		GL32.glBindBuffer(GL32.GL_ELEMENT_ARRAY_BUFFER, this.ebo);
		GL32.glUseProgram(this.prog);
		
		GL32.glDepthMask(this.depthWrite);
		GL32.glBlendFunc(this.blendSrc, this.blendDst);
		if (this.depth)
		{
			GL32.glEnable(GL32.GL_DEPTH_TEST);
		}
		else
		{
			GL32.glDisable(GL32.GL_DEPTH_TEST);
		}
		GL32.glDepthFunc(this.depthFunc);
		
		if (this.stencil)
		{
			GL32.glEnable(GL32.GL_STENCIL_TEST);
		}
		else
		{
			GL32.glDisable(GL32.GL_STENCIL_TEST);
		}
		GL32.glStencilFunc(this.stencilFunc, this.stencilRef, this.stencilMask);
		
		GL32.glViewport(this.view[0], this.view[1], this.view[2], this.view[3]);
		if (this.cull)
		{
			GL32.glEnable(GL32.GL_CULL_FACE);
		}
		else
		{
			GL32.glDisable(GL32.GL_CULL_FACE);
		}
		GL32.glCullFace(this.cullMode);
		GL32.glPolygonMode(GL32.GL_FRONT_AND_BACK, this.polyMode);
	}
	
}
