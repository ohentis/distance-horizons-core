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

package com.seibel.distanthorizons.core.render.renderer;

import com.seibel.distanthorizons.api.enums.config.EGpuUploadMethod;
import com.seibel.distanthorizons.api.enums.config.ELoggerMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.logging.ConfigBasedSpamLogger;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.glObject.GLState;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLElementBuffer;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.core.render.glObject.shader.ShaderProgram;
import com.seibel.distanthorizons.core.render.glObject.vertexAttribute.VertexAttribute;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.coreapi.util.math.Mat4f;
import com.seibel.distanthorizons.coreapi.util.math.Vec3d;
import com.seibel.distanthorizons.coreapi.util.math.Vec3f;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.opengl.GL32;

import java.awt.*;
import java.io.Closeable;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.PriorityBlockingQueue;

public class DebugRenderer
{
	public static DebugRenderer INSTANCE = new DebugRenderer();
	public DebugRenderer() { }
	
	public static final ConfigBasedLogger logger = new ConfigBasedLogger(
			LogManager.getLogger(TestRenderer.class), () -> ELoggerMode.LOG_ALL_TO_CHAT);
	public static final ConfigBasedSpamLogger spamLogger = new ConfigBasedSpamLogger(
			LogManager.getLogger(TestRenderer.class), () -> ELoggerMode.LOG_ALL_TO_CHAT, 1);
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	// A box from 0,0,0 to 1,1,1
	private static final float[] box_vertices = {
			// Pos x y z
			0, 0, 0,
			1, 0, 0,
			1, 1, 0,
			0, 1, 0,
			0, 0, 1,
			1, 0, 1,
			1, 1, 1,
			0, 1, 1,
	};
	
	private static final int[] box_outline_indices = {
			0, 1,
			1, 2,
			2, 3,
			3, 0,
			
			4, 5,
			5, 6,
			6, 7,
			7, 4,
			
			0, 4,
			1, 5,
			2, 6,
			3, 7,
	};
	
	public static final class Box
	{
		public Vec3f a;
		public Vec3f b;
		public Color color;
		
		public Box(Vec3f a, Vec3f b, Color color)
		{
			this.a = a;
			this.b = b;
			this.color = color;
		}
		
		public Box(Vec3f a, Vec3f b, Color color, Vec3f margin)
		{
			this.a = a;
			this.a.add(margin);
			this.b = b;
			this.b.subtract(margin);
			this.color = color;
		}
		
		public Box(DhLodPos pos, float minY, float maxY, float marginPercent, Color color)
		{
			DhBlockPos2D blockMin = pos.getCornerBlockPos();
			DhBlockPos2D blockMax = blockMin.add(pos.getBlockWidth(), pos.getBlockWidth());
			float edge = pos.getBlockWidth() * marginPercent;
			Vec3f a = new Vec3f(blockMin.x + edge, minY, blockMin.z + edge);
			Vec3f b = new Vec3f(blockMax.x - edge, maxY, blockMax.z - edge);
			this.a = a;
			this.b = b;
			this.color = color;
		}
		
		public Box(DhLodPos pos, float y, float yDiff, Object hash, float marginPercent, Color color)
		{
			float hashY = ((float) hash.hashCode() / Integer.MAX_VALUE) * yDiff;
			DhBlockPos2D blockMin = pos.getCornerBlockPos();
			DhBlockPos2D blockMax = blockMin.add(pos.getBlockWidth(), pos.getBlockWidth());
			float edge = pos.getBlockWidth() * marginPercent;
			Vec3f a = new Vec3f(blockMin.x + edge, hashY, blockMin.z + edge);
			Vec3f b = new Vec3f(blockMax.x - edge, hashY, blockMax.z - edge);
			this.a = a;
			this.b = b;
			this.color = color;
		}
		
		public Box(DhSectionPos pos, float minY, float maxY, float marginPercent, Color color)
		{
			this(pos.getSectionBBoxPos(), minY, maxY, marginPercent, color);
		}
		
		public Box(DhSectionPos pos, float y, float yDiff, Object hash, float marginPercent, Color color)
		{
			this(pos.getSectionBBoxPos(), y, yDiff, hash, marginPercent, color);
		}
		
	}
	
	ShaderProgram basicShader;
	GLVertexBuffer boxBuffer;
	GLElementBuffer boxOutlineBuffer;
	VertexAttribute va;
	boolean init = false;
	
	private final LinkedList<WeakReference<IDebugRenderable>> renderers = new LinkedList<>();
	
	public static final class BoxParticle implements Comparable<BoxParticle>
	{
		public Box box;
		public long startTime;
		public long duration;
		public float yChange;
		
		public BoxParticle(Box box, long startTime, long duration, float yChange)
		{
			this.box = box;
			this.startTime = startTime;
			this.duration = duration;
			this.yChange = yChange;
		}
		
		public BoxParticle(Box box, long ns, float yChange)
		{
			this(box, System.nanoTime(), ns, yChange);
		}
		
		public BoxParticle(Box box, double s, float yChange)
		{
			this(box, System.nanoTime(), (long) (s * 1000000000), yChange);
		}
		
		@Override
		public int compareTo(@NotNull DebugRenderer.BoxParticle o)
		{
			return Long.compare(startTime + duration, o.startTime + o.duration);
		}
		
		Box getBox()
		{
			long now = System.nanoTime();
			float percent = (now - startTime) / (float) duration;
			percent = (float) Math.pow(percent, 4);
			float yDiff = yChange * percent;
			return new Box(new Vec3f(box.a.x, box.a.y + yDiff, box.a.z), new Vec3f(box.b.x, box.b.y + yDiff, box.b.z), box.color);
		}
		
		boolean isDead(long time)
		{
			return time - startTime > duration;
		}
		
	}
	
	public static final class BoxWithLife implements IDebugRenderable, Closeable
	{
		public Box box;
		public BoxParticle particaleOnClose;
		
		public BoxWithLife(Box box, long ns, float yChange, Color deathColor)
		{
			this.box = box;
			this.particaleOnClose = new BoxParticle(new Box(box.a, box.b, deathColor), -1, ns, yChange);
			DebugRenderer.register(this);
		}
		
		public BoxWithLife(Box box, long ns, float yChange)
		{
			this(box, ns, yChange, box.color);
		}
		
		public BoxWithLife(Box box, double s, float yChange, Color deathColor)
		{
			this.box = box;
			this.particaleOnClose = new BoxParticle(new Box(box.a, box.b, deathColor), s, yChange);
		}
		
		public BoxWithLife(Box box, double s, float yChange)
		{
			this(box, s, yChange, box.color);
		}
		
		@Override
		public void debugRender(DebugRenderer r)
		{
			r.renderBox(box);
		}
		
		@Override
		public void close()
		{
			makeParticle(new BoxParticle(particaleOnClose.getBox(), System.nanoTime(), particaleOnClose.duration, particaleOnClose.yChange));
			DebugRenderer.unregister(this);
		}
		
	}
	
	private final PriorityBlockingQueue<BoxParticle> particles = new PriorityBlockingQueue<>();
	
	public static void unregister(IDebugRenderable r)
	{
		if (INSTANCE == null) return;
		INSTANCE.removeRenderer(r);
	}
	
	public static void makeParticle(BoxParticle particle)
	{
		if (INSTANCE == null) return;
		if (!Config.Client.Advanced.Debugging.debugWireframeRendering.get()) return;
		INSTANCE.particles.add(particle);
	}
	
	private void removeRenderer(IDebugRenderable r)
	{
		synchronized (renderers)
		{
			Iterator<WeakReference<IDebugRenderable>> it = renderers.iterator();
			while (it.hasNext())
			{
				WeakReference<IDebugRenderable> ref = it.next();
				if (ref.get() == null)
				{
					it.remove();
					continue;
				}
				if (ref.get() == r)
				{
					it.remove();
					return;
				}
			}
		}
	}
	
	public void init()
	{
		if (init) return;
		init = true;
		va = VertexAttribute.create();
		va.bind();
		// Pos\
		va.setVertexAttribute(0, 0, VertexAttribute.VertexPointer.addVec3Pointer(false));
		va.completeAndCheck(Float.BYTES * 3);
		basicShader = new ShaderProgram("shaders/debug/vert.vert", "shaders/debug/frag.frag",
				"fragColor", new String[]{"vPosition"});
		createBuffer();
	}
	
	private void createBuffer()
	{
		ByteBuffer buffer = ByteBuffer.allocateDirect(box_vertices.length * Float.BYTES);
		buffer.order(ByteOrder.nativeOrder());
		buffer.asFloatBuffer().put(box_vertices);
		buffer.rewind();
		boxBuffer = new GLVertexBuffer(false);
		boxBuffer.bind();
		boxBuffer.uploadBuffer(buffer, 8, EGpuUploadMethod.DATA, box_vertices.length * Float.BYTES);
		
		buffer = ByteBuffer.allocateDirect(box_outline_indices.length * Integer.BYTES);
		buffer.order(ByteOrder.nativeOrder());
		buffer.asIntBuffer().put(box_outline_indices);
		buffer.rewind();
		boxOutlineBuffer = new GLElementBuffer(false);
		boxOutlineBuffer.bind();
		boxOutlineBuffer.uploadBuffer(buffer, EGpuUploadMethod.DATA, box_outline_indices.length * Integer.BYTES, GL32.GL_STATIC_DRAW);
	}
	
	public void addRenderer(IDebugRenderable r)
	{
		if (!Config.Client.Advanced.Debugging.debugWireframeRendering.get()) return;
		synchronized (renderers)
		{
			renderers.add(new WeakReference<>(r));
		}
	}
	
	public static void register(IDebugRenderable r)
	{
		if (INSTANCE == null) return;
		INSTANCE.addRenderer(r);
	}
	
	private Mat4f transform_this_frame;
	private Vec3f camf;
	
	public void renderBox(Box box)
	{
		Mat4f boxTransform = Mat4f.createTranslateMatrix(box.a.x - camf.x, box.a.y - camf.y, box.a.z - camf.z);
		boxTransform.multiply(Mat4f.createScaleMatrix(box.b.x - box.a.x, box.b.y - box.a.y, box.b.z - box.a.z));
		Mat4f t = transform_this_frame.copy();
		t.multiply(boxTransform);
		basicShader.setUniform(basicShader.getUniformLocation("transform"), t);
		basicShader.setUniform(basicShader.getUniformLocation("uColor"), box.color);
		GL32.glDrawElements(GL32.GL_LINES, box_outline_indices.length, GL32.GL_UNSIGNED_INT, 0);
	}
	
	public void render(Mat4f transform)
	{
		transform_this_frame = transform;
		Vec3d cam = MC_RENDER.getCameraExactPosition();
		camf = new Vec3f((float) cam.x, (float) cam.y, (float) cam.z);
		
		GLState state = new GLState();
		init();
		
		GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, MC_RENDER.getTargetFrameBuffer());
		GL32.glViewport(0, 0, MC_RENDER.getTargetFrameBufferViewportWidth(), MC_RENDER.getTargetFrameBufferViewportHeight());
		GL32.glPolygonMode(GL32.GL_FRONT_AND_BACK, GL32.GL_LINE);
		//GL32.glLineWidth(2);
		GL32.glEnable(GL32.GL_DEPTH_TEST);
		GL32.glDisable(GL32.GL_STENCIL_TEST);
		GL32.glDisable(GL32.GL_BLEND);
		GL32.glDisable(GL32.GL_SCISSOR_TEST);
		
		basicShader.bind();
		va.bind();
		va.bindBufferToAllBindingPoint(boxBuffer.getId());
		
		boxOutlineBuffer.bind();
		
		synchronized (renderers)
		{
			Iterator<WeakReference<IDebugRenderable>> it = renderers.iterator();
			while (it.hasNext())
			{
				WeakReference<IDebugRenderable> ref = it.next();
				IDebugRenderable r = ref.get();
				if (r == null)
				{
					it.remove();
					continue;
				}
				r.debugRender(this);
			}
		}
		
		BoxParticle head = null;
		while ((head = particles.poll()) != null && head.isDead(System.nanoTime()))
		{
		}
		if (head != null)
		{
			particles.add(head);
		}
		particles.forEach(b -> renderBox(b.getBox()));
		
		state.restore();
	}
	
	
}
