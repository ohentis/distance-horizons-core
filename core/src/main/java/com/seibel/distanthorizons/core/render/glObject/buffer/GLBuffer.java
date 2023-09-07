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

package com.seibel.distanthorizons.core.render.glObject.buffer;

import com.seibel.distanthorizons.api.enums.config.EGpuUploadMethod;
import com.seibel.distanthorizons.core.enums.EGLProxyContext;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.glObject.GLProxy;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.math.UnitBytes;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;

import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

public class GLBuffer implements AutoCloseable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger(MethodHandles.lookup().lookupClass().getSimpleName());
	
	public static final double BUFFER_EXPANSION_MULTIPLIER = 1.3;
	public static final double BUFFER_SHRINK_TRIGGER = BUFFER_EXPANSION_MULTIPLIER * BUFFER_EXPANSION_MULTIPLIER;
	public static AtomicInteger count = new AtomicInteger(0);
	
	protected int id;
	public final int getId()
	{
		return id;
	}
	protected int size = 0;
	public int getSize()
	{
		return size;
	}
	protected boolean bufferStorage;
	public final boolean isBufferStorage()
	{
		return bufferStorage;
	}
	protected boolean isMapped = false;
	
	
	public GLBuffer(boolean isBufferStorage)
	{
		create(isBufferStorage);
	}
	
	
	// Should be override by subclasses
	public int getBufferBindingTarget()
	{
		return GL32.GL_COPY_READ_BUFFER;
	}
	
	public void bind()
	{
		GL32.glBindBuffer(getBufferBindingTarget(), id);
	}
	public void unbind()
	{
		GL32.glBindBuffer(getBufferBindingTarget(), 0);
	}
	
	protected void create(boolean asBufferStorage)
	{
		LodUtil.assertTrue(GLProxy.getInstance().getGlContext() != EGLProxyContext.NONE,
				"Thread [{}] tried to create a GLBuffer outside a OpenGL context.", Thread.currentThread());
		this.id = GL32.glGenBuffers();
		this.bufferStorage = asBufferStorage;
		count.getAndIncrement();
	}
	
	//DEBUG USE
	//private StackTraceElement[] firstCloseCallStack = null;
	protected void destroy(boolean async)
	{
		LodUtil.assertTrue(this.id != 0, "Buffer double close!");
		if (async && GLProxy.getInstance().getGlContext() != EGLProxyContext.PROXY_WORKER)
		{
			GLProxy.getInstance().recordOpenGlCall(() -> destroy((false)));
		}
		else
		{
			GL32.glDeleteBuffers(id);
			//firstCloseCallStack = Thread.currentThread().getStackTrace();
			id = 0;
			size = 0;
			if (count.decrementAndGet() == 0)
				LOGGER.info("All GLBuffer is freed.");
		}
	}
	
	// Requires already binded
	protected void uploadBufferStorage(ByteBuffer bb, int bufferStorageHint)
	{
		LodUtil.assertTrue(bufferStorage, "Buffer is not bufferStorage but its trying to use bufferStorage upload method!");
		int bbSize = bb.limit() - bb.position();
		destroy(false);
		create(true);
		bind();
		GL44.glBufferStorage(getBufferBindingTarget(), bb, bufferStorageHint);
		size = bbSize;
	}
	
	// Requires already binded
	protected void uploadBufferData(ByteBuffer bb, int bufferDataHint)
	{
		LodUtil.assertTrue(!bufferStorage, "Buffer is bufferStorage but its trying to use bufferData upload method!");
		int bbSize = bb.limit() - bb.position();
		GL32.glBufferData(getBufferBindingTarget(), bb, bufferDataHint);
		size = bbSize;
	}
	
	// Requires already binded
	protected void uploadSubData(ByteBuffer bb, int maxExpansionSize, int bufferDataHint)
	{
		LodUtil.assertTrue(!bufferStorage, "Buffer is bufferStorage but its trying to use subData upload method!");
		int bbSize = bb.limit() - bb.position();
		if (size < bbSize || size > bbSize * BUFFER_SHRINK_TRIGGER)
		{
			int newSize = (int) (bbSize * BUFFER_EXPANSION_MULTIPLIER);
			if (newSize > maxExpansionSize) newSize = maxExpansionSize;
			GL32.glBufferData(getBufferBindingTarget(), newSize, bufferDataHint);
			size = newSize;
		}
		GL32.glBufferSubData(getBufferBindingTarget(), 0, bb);
	}
	
	// Requires already binded
	public void uploadBuffer(ByteBuffer bb, EGpuUploadMethod uploadMethod, int maxExpansionSize, int bufferHint)
	{
		LodUtil.assertTrue(!uploadMethod.useEarlyMapping, "UploadMethod signal that this should use Mapping instead of uploadBuffer!");
		int bbSize = bb.limit() - bb.position();
		LodUtil.assertTrue(bbSize <= maxExpansionSize, "maxExpansionSize is {} but buffer size is {}!", maxExpansionSize, bbSize);
		GLProxy.GL_LOGGER.debug("Uploading buffer with {}.", new UnitBytes(bbSize));
		// If size is zero, just ignore it.
		if (bbSize == 0) return;
		boolean useBuffStorage = uploadMethod.useBufferStorage;
		if (useBuffStorage != bufferStorage)
		{
			destroy(false);
			create(useBuffStorage);
			bind();
		}
		switch (uploadMethod)
		{
			case AUTO:
				LodUtil.assertNotReach("GpuUploadMethod AUTO must be resolved before call to uploadBuffer()!");
			case BUFFER_STORAGE:
				uploadBufferStorage(bb, bufferHint);
				break;
			case DATA:
				uploadBufferData(bb, bufferHint);
				break;
			case SUB_DATA:
				uploadSubData(bb, maxExpansionSize, bufferHint);
				break;
			default:
				LodUtil.assertNotReach("Unknown GpuUploadMethod!");
		}
	}
	
	public ByteBuffer mapBuffer(int targetSize, EGpuUploadMethod uploadMethod, int maxExpensionSize, int bufferHint, int mapFlags)
	{
		LodUtil.assertTrue(targetSize != 0, "MapBuffer targetSize is 0");
		LodUtil.assertTrue(uploadMethod.useEarlyMapping, "Upload method must be one that use early mappings in order to call mapBuffer");
		LodUtil.assertTrue(!isMapped, "Buffer is already mapped");
		
		boolean useBuffStorage = uploadMethod.useBufferStorage;
		if (useBuffStorage != bufferStorage)
		{
			destroy(false);
			create(useBuffStorage);
		}
		bind();
		ByteBuffer vboBuffer;
		
		if (size < targetSize || size > targetSize * BUFFER_SHRINK_TRIGGER)
		{
			int newSize = (int) (targetSize * BUFFER_EXPANSION_MULTIPLIER);
			if (newSize > maxExpensionSize) newSize = maxExpensionSize;
			size = newSize;
			if (bufferStorage)
			{
				GL32.glDeleteBuffers(id);
				id = GL32.glGenBuffers();
				GL32.glBindBuffer(getBufferBindingTarget(), id);
				GL32.glBindBuffer(getBufferBindingTarget(), id);
				GL44.glBufferStorage(getBufferBindingTarget(), newSize, bufferHint);
			}
			else
			{
				GL32.glBufferData(GL32.GL_ARRAY_BUFFER, newSize, bufferHint);
			}
		}
		
		vboBuffer = GL32.glMapBufferRange(GL32.GL_ARRAY_BUFFER, 0, targetSize, mapFlags);
		isMapped = true;
		return vboBuffer;
	}
	
	// Requires already binded
	public void unmapBuffer()
	{
		LodUtil.assertTrue(isMapped, "Buffer is not mapped");
		bind();
		GL32.glUnmapBuffer(getBufferBindingTarget());
		isMapped = false;
	}
	
	@Override
	public void close()
	{
		destroy(true);
	}
	
	@Override
	public String toString()
	{
		return (bufferStorage ? "" : "Static-") + getClass().getSimpleName() +
				"[id:" + id + ",size:" + size + (isMapped ? ",MAPPED" : "") + "]";
	}
	
}
