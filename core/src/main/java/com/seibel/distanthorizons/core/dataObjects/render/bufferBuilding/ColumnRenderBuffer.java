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

package com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.render.glObject.GLProxy;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.StatsMap;
import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.*;

/**
 * Java representation of one or more OpenGL buffers for rendering.
 *
 * @see ColumnRenderBufferBuilder
 */
public class ColumnRenderBuffer implements AutoCloseable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	private static final IMinecraftClientWrapper minecraftClient = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	
	private static final long MAX_BUFFER_UPLOAD_TIMEOUT_NANOSECONDS = 1_000_000;
	
	/** number of bytes a single quad takes */
	public static final int QUADS_BYTE_SIZE = LodUtil.LOD_VERTEX_FORMAT.getByteSize() * 4;
	/** how big a single VBO can be in bytes */
	public static final int MAX_VBO_BYTE_SIZE = 10 * 1024 * 1024; // 10 MB
	public static final int MAX_QUADS_PER_BUFFER = MAX_VBO_BYTE_SIZE / QUADS_BYTE_SIZE;
	public static final int FULL_SIZED_BUFFER = MAX_QUADS_PER_BUFFER * QUADS_BYTE_SIZE;
	
	
	
	
	public final DhBlockPos pos;
	
	public boolean buffersUploaded = false;
	
	private GLVertexBuffer[] vbos;
	private GLVertexBuffer[] vbosTransparent;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public ColumnRenderBuffer(DhBlockPos pos)
	{
		this.pos = pos;
		this.vbos = new GLVertexBuffer[0];
		this.vbosTransparent = new GLVertexBuffer[0];
	}
	
	
	
	
	
	//==================//
	// buffer uploading //
	//==================//
	
	/** Should be run on a DH thread. */
	public void uploadBuffer(LodQuadBuilder builder, EDhApiGpuUploadMethod gpuUploadMethod) throws InterruptedException
	{
		LodUtil.assertTrue(DhApi.isDhThread(), "Buffer uploading needs to be done on a DH thread to prevent locking up any MC threads.");
		
		
		// upload on MC's render thread
		CompletableFuture<Void> uploadFuture = new CompletableFuture<>();
		minecraftClient.executeOnRenderThread(() ->
		{
			try
			{
				this.uploadBuffers(builder, gpuUploadMethod);
				uploadFuture.complete(null);
			}
			catch (InterruptedException e)
			{
				throw new CompletionException(e);
			}
		});
		
		
		try
		{
			// wait for the upload to finish
			uploadFuture.get(5_000, TimeUnit.MILLISECONDS);
		}
		catch (ExecutionException e)
		{
			LOGGER.warn("Error uploading builder ["+builder+"] synchronously. Error: "+e.getMessage(), e);
		}
		catch (TimeoutException e)
		{
			// timeouts can be ignored because it generally means the
			// MC Render thread executor was closed 
			//LOGGER.warn("Error uploading builder ["+builder+"] synchronously. Error: "+e.getMessage(), e);
		}
	}
	private void uploadBuffers(LodQuadBuilder builder, EDhApiGpuUploadMethod method) throws InterruptedException
	{
		// uploading mapped buffers used to be done here,
		// however due to a memory leak and complication with the previous code,
		// now we only allow direct uploading.
		// (There's also insufficient data to state whether mapped buffers are necessary
		// for DH to upload without stuttering the main thread)
		
		this.vbos = makeAndUploadBuffers(builder, method, this.vbos, builder.makeOpaqueVertexBuffers());
		this.vbosTransparent = makeAndUploadBuffers(builder, method, this.vbosTransparent, builder.makeTransparentVertexBuffers());
		
		this.buffersUploaded = true;
	}
	/** This resizes and returns the vbo array if necessary based on the amount of data needed for this area. */
	private static GLVertexBuffer[] makeAndUploadBuffers(LodQuadBuilder builder, EDhApiGpuUploadMethod method, GLVertexBuffer[] vbos, ArrayList<ByteBuffer> buffers) throws InterruptedException
	{
		try
		{
			vbos = resizeBuffer(vbos, buffers.size());
			uploadBuffersDirect(vbos, buffers, method);
		}
		finally
		{
			// all the buffers must be manually freed to prevent memory leaks
			if (buffers != null)
			{
				for (ByteBuffer buffer : buffers)
				{
					MemoryUtil.memFree(buffer);
				}
			}
		}
		
		// return the array in case it was resized
		return vbos;
	}
	private static void uploadBuffersDirect(GLVertexBuffer[] vbos, ArrayList<ByteBuffer> byteBuffers, EDhApiGpuUploadMethod method) throws InterruptedException
	{
		int vboIndex = 0;
		for (int i = 0; i < byteBuffers.size(); i++)
		{
			if (vboIndex >= vbos.length)
			{
				throw new RuntimeException("Too many vertex buffers!!");
			}
			
			
			// get or create the VBO
			if (vbos[vboIndex] == null)
			{
				vbos[vboIndex] = new GLVertexBuffer(method.useBufferStorage);
			}
			GLVertexBuffer vbo = vbos[vboIndex];
			
			
			ByteBuffer buffer = byteBuffers.get(i);
			int size = buffer.limit() - buffer.position();
			
			try
			{
				vbo.bind();
				vbo.uploadBuffer(buffer, size / LodUtil.LOD_VERTEX_FORMAT.getByteSize(), method, FULL_SIZED_BUFFER);
			}
			catch (Exception e)
			{
				vbos[vboIndex] = null;
				vbo.close();
				LOGGER.error("Failed to upload buffer: ", e);
			}
			
			vboIndex++;
		}
		
		if (vboIndex < vbos.length)
		{
			throw new RuntimeException("Too few vertex buffers!!");
		}
	}
	
	
	
	
	
	//========//
	// render //
	//========//
	
	/** @return true if something was rendered, false otherwise */
	public boolean renderOpaque(LodRenderer renderContext, DhApiRenderParam renderEventParam)
	{
		boolean hasRendered = false;
		renderContext.setModelViewMatrixOffset(this.pos, renderEventParam);
		for (GLVertexBuffer vbo : this.vbos)
		{
			if (vbo == null)
			{
				continue;
			}
			
			if (vbo.getVertexCount() == 0)
			{
				continue;
			}
			
			hasRendered = true;
			renderContext.drawVbo(vbo);
			//LodRenderer.tickLogger.info("Vertex buffer: {}", vbo);
		}
		return hasRendered;
	}
	
	/** @return true if something was rendered, false otherwise */
	public boolean renderTransparent(LodRenderer renderContext, DhApiRenderParam renderEventParam)
	{
		boolean hasRendered = false;
		
		try
		{
			// can throw an IllegalStateException if the GL program was freed before it should've been
			renderContext.setModelViewMatrixOffset(this.pos, renderEventParam);
			
			for (GLVertexBuffer vbo : this.vbosTransparent)
			{
				if (vbo == null)
				{
					continue;
				}
				
				if (vbo.getVertexCount() == 0)
				{
					continue;
				}
				
				hasRendered = true;
				renderContext.drawVbo(vbo);
				//LodRenderer.tickLogger.info("Vertex buffer: {}", vbo);
			}
		}
		catch (IllegalStateException e)
		{
			LOGGER.error("renderContext program doesn't exist for pos: "+this.pos, e);
		}
		
		return hasRendered;
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	/** can be used when debugging */
	public boolean hasNonNullVbos() { return this.vbos != null || this.vbosTransparent != null; }
	
	/** can be used when debugging */
	public int vboBufferCount() 
	{
		int count = 0;
		
		if (this.vbos != null)
		{
			count += this.vbos.length;
		}
		
		if (this.vbosTransparent != null)
		{
			count += this.vbosTransparent.length;
		}
		
		return count;
	}
	
	public void debugDumpStats(StatsMap statsMap)
	{
		statsMap.incStat("RenderBuffers");
		statsMap.incStat("SimpleRenderBuffers");
		for (GLVertexBuffer vertexBuffer : vbos)
		{
			if (vertexBuffer != null)
			{
				statsMap.incStat("VBOs");
				if (vertexBuffer.getSize() == FULL_SIZED_BUFFER)
				{
					statsMap.incStat("FullsizedVBOs");
				}
				
				if (vertexBuffer.getSize() == 0)
				{
					GLProxy.GL_LOGGER.warn("VBO with size 0");
				}
				statsMap.incBytesStat("TotalUsage", vertexBuffer.getSize());
			}
		}
	}
	
	public static GLVertexBuffer[] resizeBuffer(GLVertexBuffer[] vbos, int newSize)
	{
		if (vbos.length == newSize)
		{
			return vbos;
		}
		
		GLVertexBuffer[] newVbos = new GLVertexBuffer[newSize];
		System.arraycopy(vbos, 0, newVbos, 0, Math.min(vbos.length, newSize));
		if (newSize < vbos.length)
		{
			for (int i = newSize; i < vbos.length; i++)
			{
				if (vbos[i] != null)
				{
					vbos[i].close();
				}
			}
		}
		return newVbos;
	}
	
	
	
	
	//================//
	// base overrides //
	//================//
	
	/**
	 * This method is called when object is no longer in use.
	 * Called either after uploadBuffers() returned false (On buffer Upload
	 * thread), or by others when the object is not being used. (not in build,
	 * upload, or render state). 
	 */
	@Override
	public void close()
	{
		this.buffersUploaded = false;
		
		GLProxy.getInstance().queueRunningOnRenderThread(() ->
		{
			for (GLVertexBuffer buffer : this.vbos)
			{
				if (buffer != null)
				{
					buffer.destroyAsync();
				}
			}
			
			for (GLVertexBuffer buffer : this.vbosTransparent)
			{
				if (buffer != null)
				{
					buffer.destroyAsync();
				}
			}
		});
	}
	
}
