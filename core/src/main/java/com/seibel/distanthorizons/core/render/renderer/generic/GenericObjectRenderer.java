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

package com.seibel.distanthorizons.core.render.renderer.generic;

import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.api.enums.config.EDhApiLoggerMode;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderRegister;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBoxGroupShading;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;

import java.awt.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
	private GLVertexBuffer boxVertexBuffer;
	private GLElementBuffer boxIndexBuffer;
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
	
	private int northShadingUniform;
	private int southShadingUniform;
	private int eastShadingUniform;
	private int westShadingUniform;
	private int topShadingUniform;
	private int bottomShadingUniform;
	
	
	private final ConcurrentHashMap<Long, RenderableBoxGroup> boxGroupById = new ConcurrentHashMap<>();
	
	
	
	/** A box from 0,0,0 to 1,1,1 */
	private static final float[] BOX_VERTICES = {
			// Pos x y z
			
			// min X, vertical face
			0, 0, 0,
			1, 0, 0,
			1, 1, 0,
			0, 1, 0,
			// max X, vertical face
			0, 1, 1,
			1, 1, 1,
			1, 0, 1,
			0, 0, 1,
			
			// min Z, vertical face
			0, 0, 1,
			0, 0, 0,
			0, 1, 0,
			0, 1, 1,
			// max Z, vertical face
			1, 0, 1,
			1, 1, 1,
			1, 1, 0,
			1, 0, 0,
			
			// min Y, horizontal face
			0, 0, 1,
			1, 0, 1,
			1, 0, 0,
			0, 0, 0,
			// max Y, horizontal face
			0, 1, 1,
			1, 1, 1,
			1, 1, 0,
			0, 1, 0,
	};
	
	private static final int[] BOX_INDICES = {
			// min X, vertical face
			2, 1, 0,    
			0, 3, 2,
			// max X, vertical face
			6, 5, 4,
			4, 7, 6,
			
			// min Z, vertical face
			10, 9, 8,
			8, 11, 10,
			// max Z, vertical face
			14, 13, 12,
			12, 15, 14,
			
			// min Y, horizontal face
			18, 17, 16,
			16, 19, 18,
			// max Y, horizontal face
			20, 21, 22, 
			22, 23, 20,
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
		//this.shadingModeUniform = this.shader.getUniformLocation("uShadingMode");
		this.northShadingUniform = this.shader.getUniformLocation("uNorthShading");
		this.southShadingUniform = this.shader.getUniformLocation("uSouthShading");
		this.eastShadingUniform = this.shader.getUniformLocation("uEastShading");
		this.westShadingUniform = this.shader.getUniformLocation("uWestShading");
		this.topShadingUniform = this.shader.getUniformLocation("uTopShading");
		this.bottomShadingUniform = this.shader.getUniformLocation("uBottomShading");
		
		
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
		this.boxVertexBuffer = new GLVertexBuffer(false);
		this.boxVertexBuffer.bind();
		this.boxVertexBuffer.uploadBuffer(boxVerticesBuffer, 8, EDhApiGpuUploadMethod.DATA, BOX_VERTICES.length * Float.BYTES);
		
		
		// box vertex indexes
		ByteBuffer solidIndexBuffer = ByteBuffer.allocateDirect(BOX_INDICES.length * Integer.BYTES);
		solidIndexBuffer.order(ByteOrder.nativeOrder());
		solidIndexBuffer.asIntBuffer().put(BOX_INDICES);
		solidIndexBuffer.rewind();
		this.boxIndexBuffer = new GLElementBuffer(false);
		this.boxIndexBuffer.uploadBuffer(solidIndexBuffer, EDhApiGpuUploadMethod.DATA, BOX_INDICES.length * Integer.BYTES, GL32.GL_STATIC_DRAW);
		this.boxIndexBuffer.bind();
		
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
		
		
		long id = boxGroup.getId();
		if (this.boxGroupById.containsKey(id))
		{
			throw new IllegalArgumentException("A box group with the ID [" + id + "] is already present.");
		}
		
		this.boxGroupById.put(id, boxGroup);
	}
	
	@Override
	public IDhApiRenderableBoxGroup remove(long id) { return this.boxGroupById.remove(id); }
	
	public void clear() { this.boxGroupById.clear(); }
	
	
	
	//===========//
	// rendering //
	//===========//
	
	/**
	 * @param renderingWithSsao 
	 *      if true that means this render call is happening before the SSAO pass
     *      and any objects rendered in this pass will have SSAO applied to them.
	 */
	public void render(DhApiRenderParam renderEventParam, IProfilerWrapper profiler, boolean renderingWithSsao)
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
		this.va.bindBufferToAllBindingPoints(this.boxVertexBuffer.getId());
		
		this.boxIndexBuffer.bind();
		
		Mat4f projectionMvmMatrix = new Mat4f(renderEventParam.dhProjectionMatrix);
		projectionMvmMatrix.multiply(renderEventParam.dhModelViewMatrix);
		
		Vec3d camPosDouble = MC_RENDER.getCameraExactPosition();
		Vec3f camPos = new Vec3f((float) camPosDouble.x, (float) camPosDouble.y, (float) camPosDouble.z);
		
		
		
		// rendering //
		
		Collection<RenderableBoxGroup> boxList = this.boxGroupById.values();
		for (RenderableBoxGroup boxGroup : boxList)
		{
			// skip boxes that shouldn't render this pass
			if (boxGroup.ssaoEnabled == renderingWithSsao)
			{
				profiler.popPush("render prep");
				boxGroup.preRender(renderEventParam);
				
				// ignore inactive groups
				if (boxGroup.active)
				{
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
					
					boxGroup.postRender(renderEventParam);
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
		
		DhApiRenderableBoxGroupShading shading = boxGroup.shading;
		if (shading == null)
		{
			shading = DhApiRenderableBoxGroupShading.getUnshaded();
		}
		this.shader.setUniform(this.northShadingUniform, shading.north);
		this.shader.setUniform(this.southShadingUniform, shading.south);
		this.shader.setUniform(this.eastShadingUniform, shading.east);
		this.shader.setUniform(this.westShadingUniform, shading.west);
		this.shader.setUniform(this.topShadingUniform, shading.top);
		this.shader.setUniform(this.bottomShadingUniform, shading.bottom);
		
		
		
		
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
		if (boxGroup.uploadedBoxCount > 0)
		{
			GL32.glDrawElementsInstanced(GL32.GL_TRIANGLES, BOX_INDICES.length, GL32.GL_UNSIGNED_INT, 0, boxGroup.uploadedBoxCount);
		}
		
		
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
			this.renderBox(boxGroup, box, transformMatrix, camPos);
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
		
		GL32.glDrawElements(GL32.GL_TRIANGLES, BOX_INDICES.length, GL32.GL_UNSIGNED_INT, 0);
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
