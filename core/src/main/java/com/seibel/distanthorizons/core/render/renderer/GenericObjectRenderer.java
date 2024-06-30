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

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.api.enums.config.EDhApiLoggerMode;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderRegister;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.ConfigBasedSpamLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.glObject.GLProxy;
import com.seibel.distanthorizons.core.render.glObject.GLState;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLElementBuffer;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.core.render.glObject.shader.ShaderProgram;
import com.seibel.distanthorizons.core.render.glObject.vertexAttribute.AbstractVertexAttribute;
import com.seibel.distanthorizons.core.render.glObject.vertexAttribute.VertexPointer;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.coreapi.util.math.Mat4f;
import com.seibel.distanthorizons.coreapi.util.math.Vec3d;
import com.seibel.distanthorizons.coreapi.util.math.Vec3f;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;

import javax.annotation.Nullable;
import java.awt.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Spliterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

public class GenericObjectRenderer implements IDhApiCustomRenderRegister
{
	public static GenericObjectRenderer INSTANCE = new GenericObjectRenderer();
	
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	public static final ConfigBasedSpamLogger SPAM_LOGGER = new ConfigBasedSpamLogger(LogManager.getLogger(TestRenderer.class), () -> EDhApiLoggerMode.LOG_ALL_TO_CHAT, 1);
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	
	// rendering setup
	private boolean init = false;
	
	private ShaderProgram shader;
	private GLVertexBuffer vertexBuffer;
	private GLElementBuffer solidIndexBuffer;
	private AbstractVertexAttribute va;
	
	private boolean useInstancedRendering;
	private boolean vertexAttribDivisorSupported;
	private boolean instancedArraysSupported;
	
	
	// instance data
	private int instanceTranslateVBO;
	private int instanceScaleVBO;
	private int instanceColorVBO;
	
	// shader uniforms
	private int directShaderTransformUniformLocation;
	private int directShaderColorUniformLocation;
	
	private int instancedShaderOffsetUniformLocation;
	private int instancedShaderCameraPosUniformLocation;
	private int instancedShaderProjectionModelViewMatrixUniformLocation;
	
	
	// TODO may need to be double buffered to prevent rendering lag
	private final Long2ReferenceOpenHashMap<DhApiRenderableBoxGroup> boxGroupById = new Long2ReferenceOpenHashMap<>();
	private final ReentrantLock mapModifyLock = new ReentrantLock();
	
	
	
	/** A box from 0,0,0 to 1,1,1 */
	private static final float[] BOX_VERTICES = {
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
	
	private static final int[] SOLID_BOX_INDICES = {
			// min Z, vertical face
			0, 3, 2,
			2, 1, 0,
			// max Z, vertical face
			4, 5, 6,
			6, 7, 4,
			
			// min X, vertical face
			7, 3, 0,
			0, 4, 7,
			// max X, vertical face
			2, 6, 5,
			5, 1, 2,
			
			// min Y, horizontal face
			1, 5, 4,
			4, 0, 1,
			// max Y, horizontal face
			3, 7, 6,
			6, 2, 3,
	};
	
	
	
	//=============//
	// constructor //
	//=============//
	
	private GenericObjectRenderer() { }
	
	public void init()
	{
		if (this.init)
		{
			return;
		}
		this.init = true;
		
		this.vertexAttribDivisorSupported = GLProxy.getInstance().vertexAttribDivisorSupported;
		this.instancedArraysSupported = GLProxy.getInstance().instancedArraysSupported;
		this.useInstancedRendering = this.vertexAttribDivisorSupported || this.instancedArraysSupported;
		if (!this.useInstancedRendering)
		{
			LOGGER.info("Instanced rendering not supported by this GPU, falling back to direct rendering. Generic object rendering may be slow.");
		}
		
		
		this.va = AbstractVertexAttribute.create();
		this.va.bind();
		// Pos
		this.va.setVertexAttribute(0, 0, VertexPointer.addVec3Pointer(false));
		this.va.completeAndCheck(Float.BYTES * 3);
		
		this.shader = new ShaderProgram(
				this.useInstancedRendering ? "shaders/genericObject/instanced/vert.vert" : "shaders/genericObject/direct/vert.vert",
				this.useInstancedRendering ? "shaders/genericObject/instanced/frag.frag" : "shaders/genericObject/direct/frag.frag",
				"fragColor", new String[]{"vPosition"});
		
		this.directShaderTransformUniformLocation = this.shader.tryGetUniformLocation("uTransform");
		this.directShaderColorUniformLocation = this.shader.tryGetUniformLocation("uColor");
		
		this.instancedShaderOffsetUniformLocation = this.shader.tryGetUniformLocation("uOffset");
		this.instancedShaderCameraPosUniformLocation = this.shader.tryGetUniformLocation("uCameraPos");
		this.instancedShaderProjectionModelViewMatrixUniformLocation = this.shader.tryGetUniformLocation("uProjectionMvm");
		
		this.createBuffers();
		
		
		
		// testing //
		
		// single giant cube
		IDhApiRenderableBoxGroup singleGiantCubeGroup = DhApi.Delayed.renderRegister.createForSingleBox(
				new DhApiRenderableBox(
						new Vec3f(0f,0f,0f), new Vec3f(16f,190f,16f),
						new Color(Color.CYAN.getRed(), Color.CYAN.getGreen(), Color.CYAN.getBlue(), 125))
				);
		DhApi.Delayed.renderRegister.add(singleGiantCubeGroup);

		// single slender cube
		singleGiantCubeGroup = DhApi.Delayed.renderRegister.createForSingleBox(
				new DhApiRenderableBox(
						new Vec3f(16f,0f,31f), new Vec3f(17f,2000f,32f),
						new Color(Color.GREEN.getRed(), Color.GREEN.getGreen(), Color.GREEN.getBlue(), 125))
		);
		DhApi.Delayed.renderRegister.add(singleGiantCubeGroup);
		
		// absolute cube group
		ArrayList<DhApiRenderableBox> absCubeList = new ArrayList<>();
		for (int i = 0; i < 18; i++)
		{
			absCubeList.add(new DhApiRenderableBox(
					new Vec3f(0f+i,150f+i,24f), new Vec3f(1f+i,151f+i,25f),
					new Color(Color.ORANGE.getRed(), Color.ORANGE.getGreen(), Color.ORANGE.getBlue())));
		}
		IDhApiRenderableBoxGroup absolutePosCubeGroup = DhApi.Delayed.renderRegister.createAbsolutePositionedGroup(absCubeList);
		DhApi.Delayed.renderRegister.add(absolutePosCubeGroup);
		
		// relative cube group
		ArrayList<DhApiRenderableBox> relCubeList = new ArrayList<>();
		for (int i = 0; i < 8; i+=2)
		{
			relCubeList.add(new DhApiRenderableBox(
					new Vec3f(0f,0f+i,0f), new Vec3f(1f,1f+i,1f),
					new Color(Color.MAGENTA.getRed(), Color.MAGENTA.getGreen(), Color.MAGENTA.getBlue())));
		}
		IDhApiRenderableBoxGroup relativePosCubeGroup = DhApi.Delayed.renderRegister.createRelativePositionedGroup(
				24f, 140f, 24f,
				relCubeList);
		relativePosCubeGroup.setPreRenderFunc((event) -> 
		{
			float x = relativePosCubeGroup.getOriginBlockX();
			x += event.partialTicks / 2;
			x %= 32;
			relativePosCubeGroup.setOriginBlockPos(x, relativePosCubeGroup.getOriginBlockY(), relativePosCubeGroup.getOriginBlockZ());
		});
		DhApi.Delayed.renderRegister.add(relativePosCubeGroup);
		
		
		
		
		// massive relative cube group
		ArrayList<DhApiRenderableBox> massRelCubeList = new ArrayList<>();
		for (int x = 0; x < 40*2; x+=2)
		{
			for (int z = 0; z < 40*2; z+=2)
			{
				massRelCubeList.add(new DhApiRenderableBox(
						new Vec3f(0f-x, 0f, 0f-z), new Vec3f(1f-x, 1f, 1f-z),
						new Color(Color.RED.getRed(), Color.RED.getGreen(), Color.RED.getBlue())));
			}
		}
		IDhApiRenderableBoxGroup massRelativePosCubeGroup = DhApi.Delayed.renderRegister.createRelativePositionedGroup(
				-25f, 140f, 0f,
				massRelCubeList);
		massRelativePosCubeGroup.setPreRenderFunc((event) ->
		{
			float y = massRelativePosCubeGroup.getOriginBlockY();
			y += event.partialTicks / 4;
			if (y > 150f)
			{
				y = 140f;
			}
			massRelativePosCubeGroup.setOriginBlockPos(massRelativePosCubeGroup.getOriginBlockX(), y, massRelativePosCubeGroup.getOriginBlockZ());
		});
		DhApi.Delayed.renderRegister.add(massRelativePosCubeGroup);
		
		
	}
	private void createBuffers()
	{
		// cube vertices 
		ByteBuffer boxVerticesBuffer = ByteBuffer.allocateDirect(BOX_VERTICES.length * Float.BYTES);
		boxVerticesBuffer.order(ByteOrder.nativeOrder());
		boxVerticesBuffer.asFloatBuffer().put(BOX_VERTICES);
		boxVerticesBuffer.rewind();
		this.vertexBuffer = new GLVertexBuffer(false);
		this.vertexBuffer.bind();
		this.vertexBuffer.uploadBuffer(boxVerticesBuffer, 8, EDhApiGpuUploadMethod.DATA, BOX_VERTICES.length * Float.BYTES);
		
		
		// cube vertex indexes
		ByteBuffer solidIndexBuffer = ByteBuffer.allocateDirect(SOLID_BOX_INDICES.length * Integer.BYTES);
		solidIndexBuffer.order(ByteOrder.nativeOrder());
		solidIndexBuffer.asIntBuffer().put(SOLID_BOX_INDICES);
		solidIndexBuffer.rewind();
		this.solidIndexBuffer = new GLElementBuffer(false);
		this.solidIndexBuffer.uploadBuffer(solidIndexBuffer, EDhApiGpuUploadMethod.DATA, SOLID_BOX_INDICES.length * Integer.BYTES, GL32.GL_STATIC_DRAW);
		this.solidIndexBuffer.bind();
		
		
		// instance buffers
		this.instanceTranslateVBO = GL32.glGenBuffers();
		this.instanceScaleVBO = GL32.glGenBuffers();
		this.instanceColorVBO = GL32.glGenBuffers();
	}
	
	
	
	//================//
	// group creation //
	//================//
	
	@Override 
	public IDhApiRenderableBoxGroup createForSingleBox(DhApiRenderableBox box)
	{
		ArrayList<DhApiRenderableBox> list = new ArrayList<>();
		list.add(box);
		return this.createAbsolutePositionedGroup(list);
	}
	
	@Override 
	public IDhApiRenderableBoxGroup createRelativePositionedGroup(float originBlockX, float originBlockY, float originBlockZ, List<DhApiRenderableBox> cubeList)
	{ return new DhApiRenderableBoxGroup(originBlockX, originBlockY, originBlockZ, cubeList, true); }
	
	@Override 
	public IDhApiRenderableBoxGroup createAbsolutePositionedGroup(List<DhApiRenderableBox> boxList)
	{ return new DhApiRenderableBoxGroup(0, 0, 0, boxList, false); }
	
	
	
	//==============//
	// registration //
	//==============//
	
	@Override
	public void add(IDhApiRenderableBoxGroup boxGroup) throws IllegalArgumentException 
	{
		try
		{
			mapModifyLock.lock();
			
			long id = boxGroup.getId();
			if (this.boxGroupById.containsKey(id))
			{
				throw new IllegalArgumentException("A cube group with the ID [" + id + "] is already present.");
			}
			
			this.boxGroupById.put(id, (DhApiRenderableBoxGroup) boxGroup);
			
			// TODO add to DB async?
		}
		finally
		{
			mapModifyLock.unlock();
		}
	}
	
	@Override
	public IDhApiRenderableBoxGroup remove(long id)
	{
		try
		{
			mapModifyLock.lock();
			// TODO remove from DB async?
			return this.boxGroupById.remove(id);
		}
		finally
		{
			mapModifyLock.unlock();
		}
	}
	
	public void clear() 
	{
		try
		{
			mapModifyLock.lock();
			this.boxGroupById.clear();
		}
		finally
		{
			mapModifyLock.unlock();
		}
	}
	
	
	
	//===========//
	// rendering //
	//===========//
	
	public void render(DhApiRenderParam renderEventParam, IProfilerWrapper profiler)
	{
		// render setup //
		profiler.push("setup");
		
		GLState glState = new GLState();
		this.init();
		
		GL32.glPolygonMode(GL32.GL_FRONT_AND_BACK, GL32.GL_FILL);
		GL32.glEnable(GL32.GL_DEPTH_TEST);
		
		GL32.glEnable(GL32.GL_BLEND);
		GL32.glBlendEquation(GL32.GL_FUNC_ADD);
		GL32.glBlendFuncSeparate(GL32.GL_SRC_ALPHA, GL32.GL_ONE_MINUS_SRC_ALPHA, GL32.GL_ONE, GL32.GL_ONE_MINUS_SRC_ALPHA);
		
		this.shader.bind();
		this.va.bind();
		this.va.bindBufferToAllBindingPoints(this.vertexBuffer.getId());
		
		this.solidIndexBuffer.bind();
		
		Mat4f projectionMvmMatrix = new Mat4f(renderEventParam.dhProjectionMatrix);
		projectionMvmMatrix.multiply(renderEventParam.dhModelViewMatrix);
		
		Vec3d camPosDouble = MC_RENDER.getCameraExactPosition();
		Vec3f camPos = new Vec3f((float) camPosDouble.x, (float) camPosDouble.y, (float) camPosDouble.z);
		
		
		
		// rendering //
		
		
		LongSet keys = boxGroupById.keySet();
		for (long key : keys)
		{
			profiler.popPush("render prep");
			
			DhApiRenderableBoxGroup boxGroup = boxGroupById.get(key);
			boxGroup.preRender(renderEventParam);
			
			if (this.useInstancedRendering)
			{
				updateCubeGroupInstanceBuffers(boxGroup, camPos, projectionMvmMatrix);
				
				profiler.popPush("rendering");
				this.renderBoxGroupInstanced(boxGroup, camPos, projectionMvmMatrix);
			}
			else
			{
				profiler.popPush("rendering");
				this.renderBoxGroupDirect(boxGroup, projectionMvmMatrix, camPos);
			}
		}
		
		
		
		// clean up //
		profiler.popPush("cleanup");
		
		this.shader.unbind();
		glState.restore();
		
		profiler.pop();
	}
	
	
	
	//=====================//
	// instanced rendering //
	//=====================//
	
	private void renderBoxGroupInstanced(DhApiRenderableBoxGroup cubeGroup, Vec3f camPos, Mat4f projectionMvmMatrix)
	{
		// update instance data //
		
		this.shader.setUniform(this.instancedShaderOffsetUniformLocation, 
				new Vec3f(
					cubeGroup.originBlockX, 
					cubeGroup.originBlockY, 
					cubeGroup.originBlockZ
				));
		
		this.shader.setUniform(this.instancedShaderCameraPosUniformLocation, 
				new Vec3f(
					camPos.x,
					camPos.y,
					camPos.z
				));
		
		this.shader.setUniform(this.instancedShaderProjectionModelViewMatrixUniformLocation,
				projectionMvmMatrix);
		
		
		
		
		// Bind instance data //
		
		GL32.glBindBuffer(GL32.GL_ARRAY_BUFFER, this.instanceColorVBO);
		GL32.glEnableVertexAttribArray(1);
		GL32.glVertexAttribPointer(1, 4, GL32.GL_FLOAT, false, 4 * Float.BYTES, 0);
		vertexAttribDivisor(1, 1);
		
		GL32.glBindBuffer(GL32.GL_ARRAY_BUFFER, this.instanceTranslateVBO);
		GL32.glEnableVertexAttribArray(2);
		vertexAttribDivisor(2, 1);
		GL32.glVertexAttribPointer(2, 3, GL32.GL_FLOAT, false, 3 * Float.BYTES, 0);
		
		GL32.glBindBuffer(GL32.GL_ARRAY_BUFFER, this.instanceScaleVBO);
		GL32.glEnableVertexAttribArray(3);
		vertexAttribDivisor(3, 1);
		GL32.glVertexAttribPointer(3, 3, GL32.GL_FLOAT, false, 3 * Float.BYTES, 0);
		
		
		// Draw instanced
		GL32.glDrawElementsInstanced(GL32.GL_TRIANGLES, SOLID_BOX_INDICES.length, GL32.GL_UNSIGNED_INT, 0, cubeGroup.size());
		
		
		// Clean up
		GL32.glDisableVertexAttribArray(1);
		GL32.glDisableVertexAttribArray(2);
		GL32.glDisableVertexAttribArray(3);
		GL32.glDisableVertexAttribArray(4);
		GL32.glDisableVertexAttribArray(5);
	}
	/** 
	 * Clean way to handle both {@link GL33#glVertexAttribDivisor} and {@link ARBInstancedArrays#glVertexAttribDivisorARB}
	 * based on which one is supported.
	 */
	private void vertexAttribDivisor(int index, int divisor)
	{
		if (this.vertexAttribDivisorSupported)
		{
			GL33.glVertexAttribDivisor(index, divisor);	
		}
		else if(this.instancedArraysSupported)
		{
			ARBInstancedArrays.glVertexAttribDivisorARB(index, divisor);
		}
		else
		{
			throw new IllegalStateException("Instanced rendering isn't supported by this machine. Direct rendering should have been used instead.");
		}
	}
	
	private void updateCubeGroupInstanceBuffers(DhApiRenderableBoxGroup cubeGroup, Vec3f camPos, Mat4f projectionMvmMatrix)
	{
		int boxCount = cubeGroup.size();
		
		
		// Prepare transformation matrices
		float[] translateData = new float[boxCount * 3];
		float[] scaleData = new float[boxCount * 3];
		for (int i = 0; i < cubeGroup.size(); i++)
		{
			DhApiRenderableBox cube = cubeGroup.get(i);
			
			System.arraycopy(new float[] 
				{
					cube.minPos.x,
					cube.minPos.y,
					cube.minPos.z
				}, 0, translateData, i * 3, 3);
			
			System.arraycopy(new float[]
				{
					cube.maxPos.x - cube.minPos.x,
					cube.maxPos.y - cube.minPos.y,
					cube.maxPos.z - cube.minPos.z
				}, 0, scaleData, i * 3, 3);
		}
		
		// Upload transformation matrices
		GL32.glBindBuffer(GL32.GL_ARRAY_BUFFER, this.instanceTranslateVBO);
		GL32.glBufferData(GL32.GL_ARRAY_BUFFER, translateData, GL32.GL_DYNAMIC_DRAW);
		GL32.glBindBuffer(GL32.GL_ARRAY_BUFFER, this.instanceScaleVBO);
		GL32.glBufferData(GL32.GL_ARRAY_BUFFER, scaleData, GL32.GL_DYNAMIC_DRAW);
		
		
		
		// Prepare colors
		float[] colors = new float[boxCount * 4];
		for (int i = 0; i < cubeGroup.size(); i++)
		{
			DhApiRenderableBox cube = cubeGroup.get(i);
			Color color = cube.color;
			int colorIndex = i * 4;
			colors[colorIndex] = color.getRed() / 255.0f;
			colors[colorIndex + 1] = color.getGreen() / 255.0f;
			colors[colorIndex + 2] = color.getBlue() / 255.0f;
			colors[colorIndex + 3] = color.getAlpha() / 255.0f;
		}
		
		// Upload colors
		GL32.glBindBuffer(GL32.GL_ARRAY_BUFFER, this.instanceColorVBO);
		GL32.glBufferData(GL32.GL_ARRAY_BUFFER, colors, GL32.GL_DYNAMIC_DRAW);
	}
	
	
	
	//==================//
	// direct rendering //
	//==================//
	
	private void renderBoxGroupDirect(DhApiRenderableBoxGroup cubeGroup, Mat4f transformMatrix, Vec3f camPos)
	{
		for (DhApiRenderableBox box : cubeGroup.cubeList)
		{
			renderBox(cubeGroup, box, transformMatrix, camPos);
		}
	}
	private void renderBox(
			DhApiRenderableBoxGroup cubeGroup, DhApiRenderableBox cube,
			Mat4f transformationMatrix, Vec3f camPos)
	{
		float originOffsetX = 0;
		float originOffsetY = 0;
		float originOffsetZ = 0;
		if (cubeGroup.positionCubesRelativeToGroupOrigin)
		{
			originOffsetX = cubeGroup.originBlockX;
			originOffsetY = cubeGroup.originBlockY;
			originOffsetZ = cubeGroup.originBlockZ;
		}
		
		Mat4f boxTransform = Mat4f.createTranslateMatrix(
				cube.minPos.x + originOffsetX - camPos.x,
				cube.minPos.y + originOffsetY - camPos.y,
				cube.minPos.z + originOffsetZ - camPos.z);
		boxTransform.multiply(Mat4f.createScaleMatrix(
				cube.maxPos.x - cube.minPos.x,
				cube.maxPos.y - cube.minPos.y,
				cube.maxPos.z - cube.minPos.z));
		Mat4f transformMatrix = transformationMatrix.copy();
		transformMatrix.multiply(boxTransform);
		this.shader.setUniform(this.directShaderTransformUniformLocation, transformMatrix);
		
		this.shader.setUniform(this.directShaderColorUniformLocation, cube.color);
		
		GL32.glDrawElements(GL32.GL_TRIANGLES , SOLID_BOX_INDICES.length, GL32.GL_UNSIGNED_INT, 0);
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	private static final class DhApiRenderableBoxGroup extends AbstractList<DhApiRenderableBox> implements IDhApiRenderableBoxGroup
	{
		public final static AtomicInteger NEXT_ID_ATOMIC_INT = new AtomicInteger(0);
		
		public final long id;
		
		/** If false the cubes will be positioned relative to the level's origin */
		public final boolean positionCubesRelativeToGroupOrigin;
		
		private final ArrayList<DhApiRenderableBox> cubeList;
		
		private float originBlockX;
		private float originBlockY;
		private float originBlockZ;
		
		@Nullable
		public Consumer<DhApiRenderParam> beforeRenderFunc;
		
		
		// setters/getters //
		
		@Override
		public long getId() { return this.id; }
		
		@Override
		public void setOriginBlockPos(float x, float y, float z)
		{
			this.originBlockX = x;
			this.originBlockY = y;
			this.originBlockZ = z;
		}
		
		@Override
		public float getOriginBlockX() { return this.originBlockX; }
		@Override
		public float getOriginBlockY() { return this.originBlockY; }
		@Override
		public float getOriginBlockZ() { return this.originBlockZ; }
		
		
		
		// constructor //
		
		public DhApiRenderableBoxGroup(float originBlockX, float originBlockY, float originBlockZ, List<DhApiRenderableBox> cubeList, boolean positionCubesRelativeToGroupOrigin)
		{
			// TODO save to database
			// TODO when?
			
			this.id = NEXT_ID_ATOMIC_INT.getAndIncrement();
			this.cubeList = new ArrayList<>(cubeList);
			
			this.originBlockX = originBlockX;
			this.originBlockY = originBlockY;
			this.originBlockZ = originBlockZ;
			this.positionCubesRelativeToGroupOrigin = positionCubesRelativeToGroupOrigin;
		}
		
		
		
		// methods //
		
		@Override
		public boolean add(DhApiRenderableBox cube) { return this.cubeList.add(cube); }
		
		@Override
		public void setPreRenderFunc(Consumer<DhApiRenderParam> func) { this.beforeRenderFunc = func; }
		
		//@Override
		public void preRender(DhApiRenderParam renderEventParam) 
		{
			if (this.beforeRenderFunc != null)
			{
				beforeRenderFunc.accept(renderEventParam);
			}
		}
		
		
		
		// overrides //
		
		@Override
		public DhApiRenderableBox get(int index) { return this.cubeList.get(index); }
		@Override 
		public int size() { return this.cubeList.size(); }
		@Override 
		public boolean removeIf(Predicate<? super DhApiRenderableBox> filter) { return this.cubeList.removeIf(filter); }
		@Override 
		public void replaceAll(UnaryOperator<DhApiRenderableBox> operator) { this.cubeList.replaceAll(operator); }
		@Override 
		public void sort(Comparator<? super DhApiRenderableBox> c) { this.cubeList.sort(c); }
		@Override 
		public void forEach(Consumer<? super DhApiRenderableBox> action) { this.cubeList.forEach(action); }
		@Override 
		public Spliterator<DhApiRenderableBox> spliterator() { return this.cubeList.spliterator(); }
		@Override 
		public Stream<DhApiRenderableBox> stream() { return this.cubeList.stream(); }
		@Override 
		public Stream<DhApiRenderableBox> parallelStream() { return this.cubeList.parallelStream(); }
		
	}
	
}
