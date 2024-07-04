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

import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.api.enums.config.EDhApiLoggerMode;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderRegister;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.ConfigBasedSpamLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.render.glObject.GLProxy;
import com.seibel.distanthorizons.core.render.glObject.GLState;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLElementBuffer;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.core.render.glObject.shader.ShaderProgram;
import com.seibel.distanthorizons.core.render.glObject.vertexAttribute.AbstractVertexAttribute;
import com.seibel.distanthorizons.core.render.glObject.vertexAttribute.VertexPointer;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.util.math.Mat4f;
import com.seibel.distanthorizons.core.util.math.Vec3d;
import com.seibel.distanthorizons.core.util.math.Vec3f;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;

import javax.annotation.Nullable;
import java.awt.*;
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Handles rendering generic groups of {@link DhApiRenderableBox}.
 * 
 * @see IDhApiCustomRenderRegister
 * @see DhApiRenderableBox
 */
public class GenericObjectRenderer implements IDhApiCustomRenderRegister
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	public static final ConfigBasedSpamLogger SPAM_LOGGER = new ConfigBasedSpamLogger(LogManager.getLogger(GenericObjectRenderer.class), () -> EDhApiLoggerMode.LOG_ALL_TO_CHAT, 1);
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	/** 
	 * Can be used to troubleshoot the renderer. 
	 * If enabled several debug objects will render around (0,150,0). 
	 */
	public static final boolean RENDER_DEBUG_OBJECTS = false;
	
	
	// rendering setup
	private boolean init = false;
	
	private ShaderProgram shader;
	private GLVertexBuffer vertexBuffer;
	private GLElementBuffer solidIndexBuffer;
	private AbstractVertexAttribute va;
	
	private boolean useInstancedRendering;
	private boolean vertexAttribDivisorSupported;
	private boolean instancedArraysSupported;
	
	
	// shader uniforms
	private int directShaderTransformUniform;
	private int directShaderColorUniform;
	
	private int instancedShaderOffsetUniform;
	private int instancedShaderCameraPosUniform;
	private int instancedShaderProjectionModelViewMatrixUniform;
	
	private int lightMapUniform;
	private int skyLightUniform;
	private int blockLightUniform;
	
	
	// TODO may need to be double buffered to prevent rendering lag
	private final Long2ReferenceOpenHashMap<RenderableBoxGroup> boxGroupById = new Long2ReferenceOpenHashMap<>();
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
	
	public GenericObjectRenderer() { }
	
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
			LOGGER.warn("Instanced rendering not supported by this GPU, falling back to direct rendering. Generic object rendering will be slow.");
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
		
		this.directShaderTransformUniform = this.shader.tryGetUniformLocation("uTransform");
		this.directShaderColorUniform = this.shader.tryGetUniformLocation("uColor");
		
		this.instancedShaderOffsetUniform = this.shader.tryGetUniformLocation("uOffset");
		this.instancedShaderCameraPosUniform = this.shader.tryGetUniformLocation("uCameraPos");
		this.instancedShaderProjectionModelViewMatrixUniform = this.shader.tryGetUniformLocation("uProjectionMvm");
		
		this.lightMapUniform = this.shader.getUniformLocation("uLightMap");
		this.skyLightUniform = this.shader.getUniformLocation("uSkyLight");
		this.blockLightUniform = this.shader.getUniformLocation("uBlockLight");
		
		this.createBuffers();
		
		if (RENDER_DEBUG_OBJECTS)
		{
			this.addGenericDebugObjects();
		}
	}
	private void createBuffers()
	{
		// box vertices 
		ByteBuffer boxVerticesBuffer = ByteBuffer.allocateDirect(BOX_VERTICES.length * Float.BYTES);
		boxVerticesBuffer.order(ByteOrder.nativeOrder());
		boxVerticesBuffer.asFloatBuffer().put(BOX_VERTICES);
		boxVerticesBuffer.rewind();
		this.vertexBuffer = new GLVertexBuffer(false);
		this.vertexBuffer.bind();
		this.vertexBuffer.uploadBuffer(boxVerticesBuffer, 8, EDhApiGpuUploadMethod.DATA, BOX_VERTICES.length * Float.BYTES);
		
		
		// box vertex indexes
		ByteBuffer solidIndexBuffer = ByteBuffer.allocateDirect(SOLID_BOX_INDICES.length * Integer.BYTES);
		solidIndexBuffer.order(ByteOrder.nativeOrder());
		solidIndexBuffer.asIntBuffer().put(SOLID_BOX_INDICES);
		solidIndexBuffer.rewind();
		this.solidIndexBuffer = new GLElementBuffer(false);
		this.solidIndexBuffer.uploadBuffer(solidIndexBuffer, EDhApiGpuUploadMethod.DATA, SOLID_BOX_INDICES.length * Integer.BYTES, GL32.GL_STATIC_DRAW);
		this.solidIndexBuffer.bind();
		
	}
	private void addGenericDebugObjects()
	{
		GenericRenderObjectFactory factory = GenericRenderObjectFactory.INSTANCE;
		
		
		// single giant box
		IDhApiRenderableBoxGroup singleGiantBoxGroup = factory.createForSingleBox(
				new DhApiRenderableBox(
						new DhApiVec3f(0f,0f,0f), new DhApiVec3f(16f,190f,16f),
						new Color(Color.CYAN.getRed(), Color.CYAN.getGreen(), Color.CYAN.getBlue(), 125))
		);
		singleGiantBoxGroup.setSkyLight(LodUtil.MAX_MC_LIGHT);
		singleGiantBoxGroup.setBlockLight(LodUtil.MAX_MC_LIGHT);
		this.add(singleGiantBoxGroup);


		// single slender box
		IDhApiRenderableBoxGroup singleTallBoxGroup = factory.createForSingleBox(
				new DhApiRenderableBox(
						new DhApiVec3f(16f,0f,31f), new DhApiVec3f(17f,2000f,32f),
						new Color(Color.GREEN.getRed(), Color.GREEN.getGreen(), Color.GREEN.getBlue(), 125))
		);
		singleTallBoxGroup.setSkyLight(LodUtil.MAX_MC_LIGHT);
		singleTallBoxGroup.setBlockLight(LodUtil.MAX_MC_LIGHT);
		this.add(singleTallBoxGroup);


		// absolute box group
		ArrayList<DhApiRenderableBox> absBoxList = new ArrayList<>();
		for (int i = 0; i < 18; i++)
		{
			absBoxList.add(new DhApiRenderableBox(
					new DhApiVec3f(0f+i,150f+i,24f), new DhApiVec3f(1f+i,151f+i,25f),
					new Color(Color.ORANGE.getRed(), Color.ORANGE.getGreen(), Color.ORANGE.getBlue())));
		}
		IDhApiRenderableBoxGroup absolutePosBoxGroup = factory.createAbsolutePositionedGroup(absBoxList);
		this.add(absolutePosBoxGroup);


		// relative box group
		ArrayList<DhApiRenderableBox> relBoxList = new ArrayList<>();
		for (int i = 0; i < 8; i+=2)
		{
			relBoxList.add(new DhApiRenderableBox(
					new DhApiVec3f(0f,0f+i,0f), new DhApiVec3f(1f,1f+i,1f),
					new Color(Color.MAGENTA.getRed(), Color.MAGENTA.getGreen(), Color.MAGENTA.getBlue())));
		}
		IDhApiRenderableBoxGroup relativePosBoxGroup = factory.createRelativePositionedGroup(
				new DhApiVec3f(24f, 140f, 24f),
				relBoxList);
		relativePosBoxGroup.setPreRenderFunc((event) ->
		{
			DhApiVec3f pos = relativePosBoxGroup.getOriginBlockPos();
			pos.x += event.partialTicks / 2;
			pos.x %= 32;
			relativePosBoxGroup.setOriginBlockPos(pos);
		});
		this.add(relativePosBoxGroup);


		// massive relative box group
		ArrayList<DhApiRenderableBox> massRelBoxList = new ArrayList<>();
		for (int x = 0; x < 50*2; x+=2)
		{
			for (int z = 0; z < 50*2; z+=2)
			{
				massRelBoxList.add(new DhApiRenderableBox(
						new DhApiVec3f(0f-x, 0f, 0f-z), new DhApiVec3f(1f-x, 1f, 1f-z),
						new Color(Color.RED.getRed(), Color.RED.getGreen(), Color.RED.getBlue())));
			}
		}
		IDhApiRenderableBoxGroup massRelativePosBoxGroup = factory.createRelativePositionedGroup(
				new DhApiVec3f(-25f, 140f, 0f),
				massRelBoxList);
		massRelativePosBoxGroup.setPreRenderFunc((event) ->
		{
			DhApiVec3f blockPos = massRelativePosBoxGroup.getOriginBlockPos();
			blockPos.y += event.partialTicks / 4;
			if (blockPos.y > 150f)
			{
				blockPos.y = 140f;

				Color newColor = (massRelativePosBoxGroup.get(0).color == Color.RED) ? Color.RED.darker() : Color.RED;
				massRelativePosBoxGroup.forEach((box) -> { box.color = newColor; });
				massRelativePosBoxGroup.triggerBoxChange();
			}

			massRelativePosBoxGroup.setOriginBlockPos(blockPos);
		});
		this.add(massRelativePosBoxGroup);
	}
	
	
	
	//==============//
	// registration //
	//==============//
	
	@Override
	public void add(IDhApiRenderableBoxGroup iBoxGroup) throws IllegalArgumentException 
	{
		if (!(iBoxGroup instanceof RenderableBoxGroup))
		{
			throw new IllegalArgumentException("Box group must be of type ["+ RenderableBoxGroup.class.getSimpleName()+"], type received: ["+(iBoxGroup != null ? iBoxGroup.getClass() : "NULL")+"].");
		}
		RenderableBoxGroup boxGroup = (RenderableBoxGroup) iBoxGroup;
		
		
		try
		{
			mapModifyLock.lock();
			
			long id = boxGroup.getId();
			if (this.boxGroupById.containsKey(id))
			{
				throw new IllegalArgumentException("A box group with the ID [" + id + "] is already present.");
			}
			
			this.boxGroupById.put(id, boxGroup);
			
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
			RenderableBoxGroup boxGroup = boxGroupById.get(key);
			// ignore inactive groups
			if (boxGroup.active)
			{
				profiler.popPush("render prep");
				boxGroup.preRender(renderEventParam);
				
				if (this.useInstancedRendering)
				{
					profiler.popPush("rendering");
					this.renderBoxGroupInstanced(boxGroup, camPos, projectionMvmMatrix);
				}
				else
				{
					profiler.popPush("rendering");
					this.renderBoxGroupDirect(boxGroup, projectionMvmMatrix, camPos);
				}
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
	
	private void renderBoxGroupInstanced(RenderableBoxGroup boxGroup, Vec3f camPos, Mat4f projectionMvmMatrix)
	{
		// update instance data //
		
		boxGroup.updateVertexAttributeData();
		
		this.shader.setUniform(this.instancedShaderOffsetUniform, 
				new Vec3f(
					boxGroup.getOriginBlockPos().x, 
					boxGroup.getOriginBlockPos().y, 
					boxGroup.getOriginBlockPos().z
				));
		
		this.shader.setUniform(this.instancedShaderCameraPosUniform, 
				new Vec3f(
					camPos.x,
					camPos.y,
					camPos.z
				));
		
		this.shader.setUniform(this.instancedShaderProjectionModelViewMatrixUniform,
				projectionMvmMatrix);
		
		this.shader.setUniform(this.lightMapUniform, 0); // TODO this should probably be passed in
		this.shader.setUniform(this.skyLightUniform, boxGroup.skyLight);
		this.shader.setUniform(this.blockLightUniform, boxGroup.blockLight);
		
		
		
		
		// Bind instance data //
		
		GL32.glBindBuffer(GL32.GL_ARRAY_BUFFER, boxGroup.instanceColorVbo);
		GL32.glEnableVertexAttribArray(1);
		GL32.glVertexAttribPointer(1, 4, GL32.GL_FLOAT, false, 4 * Float.BYTES, 0);
		this.vertexAttribDivisor(1, 1);
		
		GL32.glBindBuffer(GL32.GL_ARRAY_BUFFER, boxGroup.instanceTranslationVbo);
		GL32.glEnableVertexAttribArray(2);
		this.vertexAttribDivisor(2, 1);
		GL32.glVertexAttribPointer(2, 3, GL32.GL_FLOAT, false, 3 * Float.BYTES, 0);
		
		GL32.glBindBuffer(GL32.GL_ARRAY_BUFFER, boxGroup.instanceScaleVbo);
		GL32.glEnableVertexAttribArray(3);
		this.vertexAttribDivisor(3, 1);
		GL32.glVertexAttribPointer(3, 3, GL32.GL_FLOAT, false, 3 * Float.BYTES, 0);
		
		
		// Draw instanced
		GL32.glDrawElementsInstanced(GL32.GL_TRIANGLES, SOLID_BOX_INDICES.length, GL32.GL_UNSIGNED_INT, 0, boxGroup.size());
		
		
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
	
	
	
	
	//==================//
	// direct rendering //
	//==================//
	
	private void renderBoxGroupDirect(RenderableBoxGroup boxGroup, Mat4f transformMatrix, Vec3f camPos)
	{
		this.shader.setUniform(this.lightMapUniform, 0); // TODO this should probably be passed in
		this.shader.setUniform(this.skyLightUniform, boxGroup.skyLight);
		this.shader.setUniform(this.blockLightUniform, boxGroup.blockLight);
		
		for (DhApiRenderableBox box : boxGroup)
		{
			renderBox(boxGroup, box, transformMatrix, camPos);
		}
	}
	private void renderBox(
			RenderableBoxGroup boxGroup, DhApiRenderableBox box,
			Mat4f transformationMatrix, Vec3f camPos)
	{
		float originOffsetX = 0;
		float originOffsetY = 0;
		float originOffsetZ = 0;
		if (boxGroup.positionBoxesRelativeToGroupOrigin)
		{
			originOffsetX = boxGroup.getOriginBlockPos().x;
			originOffsetY = boxGroup.getOriginBlockPos().y;
			originOffsetZ = boxGroup.getOriginBlockPos().z;
		}
		
		Mat4f boxTransform = Mat4f.createTranslateMatrix(
				box.minPos.x + originOffsetX - camPos.x,
				box.minPos.y + originOffsetY - camPos.y,
				box.minPos.z + originOffsetZ - camPos.z);
		boxTransform.multiply(Mat4f.createScaleMatrix(
				box.maxPos.x - box.minPos.x,
				box.maxPos.y - box.minPos.y,
				box.maxPos.z - box.minPos.z));
		Mat4f transformMatrix = transformationMatrix.copy();
		transformMatrix.multiply(boxTransform);
		this.shader.setUniform(this.directShaderTransformUniform, transformMatrix);
		
		this.shader.setUniform(this.directShaderColorUniform, box.color);
		
		GL32.glDrawElements(GL32.GL_TRIANGLES, SOLID_BOX_INDICES.length, GL32.GL_UNSIGNED_INT, 0);
	}
	
	
	
	
	//=========//
	// F3 menu //
	//=========//
	
	public String getVboRenderDebugMenuString()
	{
		// get counts
		int totalCount = this.boxGroupById.size();
		int activeCount = 0;
		for (long key : this.boxGroupById.keySet())
		{
			RenderableBoxGroup renderGroup = this.boxGroupById.get(key);
			if (renderGroup.active)
			{
				activeCount++;
			}
		}
		
		
		String totalCountText = F3Screen.NUMBER_FORMAT.format(totalCount);
		String activeCountText = F3Screen.NUMBER_FORMAT.format(activeCount);
		return LodUtil.formatLog("Generic Obj Count: " + activeCountText + "/" + totalCountText);
	}
	
}
