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

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.glObject.GLProxy;
import com.seibel.distanthorizons.core.render.glObject.shader.Shader;
import com.seibel.distanthorizons.core.render.glObject.shader.ShaderProgram;
import com.seibel.distanthorizons.core.render.glObject.vertexAttribute.AbstractVertexAttribute;
import com.seibel.distanthorizons.core.render.glObject.vertexAttribute.VertexAttributePostGL43;
import com.seibel.distanthorizons.core.render.glObject.vertexAttribute.VertexAttributePreGL43;
import com.seibel.distanthorizons.core.render.glObject.vertexAttribute.VertexPointer;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.render.fog.LodFogConfig;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.coreapi.util.math.Mat4f;
import com.seibel.distanthorizons.coreapi.util.math.Vec3f;
import com.seibel.distanthorizons.core.wrapperInterfaces.IVersionConstants;

public class LodRenderProgram extends ShaderProgram
{
	public static final String VERTEX_SHADER_PATH = "shaders/standard.vert";
	public static final String VERTEX_CURVE_SHADER_PATH = "shaders/curve.vert";
	public static final String FRAGMENT_SHADER_PATH = "shaders/flat_shaded.frag";
	
	public final AbstractVertexAttribute vao;
	
	// Uniforms
	public final int combinedMatUniform;
	public final int modelOffsetUniform;
	public final int worldYOffsetUniform;
	
	public final int mircoOffsetUniform;
	
	public final int earthRadiusUniform;
	
	public final int lightMapUniform;
	
	// Fog/Clip Uniforms
	public final int clipDistanceUniform;
	
	// Noise Uniforms
	public final int noiseEnabledUniform;
	public final int noiseStepsUniform;
	public final int noiseIntensityUniform;
	public final int noiseDropoffUniform;
	
	// Debug Uniform
	public final int whiteWorldUniform;
	
	
	
	// This will bind  AbstractVertexAttribute
	public LodRenderProgram()
	{
		super(() -> Shader.loadFile(Config.Client.Advanced.Graphics.AdvancedGraphics.earthCurveRatio.get() != 0 ? VERTEX_CURVE_SHADER_PATH : VERTEX_SHADER_PATH,
						false, new StringBuilder()).toString(),
				() -> Shader.loadFile(FRAGMENT_SHADER_PATH, false, new StringBuilder()).toString(),
				"fragColor", new String[]{"vPosition", "color"});
		
		combinedMatUniform = getUniformLocation("combinedMatrix");
		modelOffsetUniform = getUniformLocation("modelOffset");
		worldYOffsetUniform = tryGetUniformLocation("worldYOffset");
		mircoOffsetUniform = getUniformLocation("mircoOffset");
		earthRadiusUniform = tryGetUniformLocation("earthRadius");
		
		lightMapUniform = getUniformLocation("lightMap");
		
		// Fog/Clip Uniforms
		clipDistanceUniform = getUniformLocation("clipDistance");
		
		// Noise Uniforms
		noiseEnabledUniform = getUniformLocation("noiseEnabled");
		noiseStepsUniform = getUniformLocation("noiseSteps");
		noiseIntensityUniform = getUniformLocation("noiseIntensity");
		noiseDropoffUniform = getUniformLocation("noiseDropoff");
		
		// Debug Uniform
		whiteWorldUniform = getUniformLocation("whiteWorld");
		
		
		// TODO: Add better use of the LODFormat thing
		int vertexByteCount = LodUtil.LOD_VERTEX_FORMAT.getByteSize();
		if (GLProxy.getInstance().VertexAttributeBufferBindingSupported)
			vao = new VertexAttributePostGL43(); // also binds AbstractVertexAttribute
		else
			vao = new VertexAttributePreGL43(); // also binds AbstractVertexAttribute
		vao.bind();
		
		// TODO comment what each attribute represents
		vao.setVertexAttribute(0, 0, VertexPointer.addUnsignedShortsPointer(4, false, true)); // 2+2+2+2 // TODO probably color, blockpos
		vao.setVertexAttribute(0, 1, VertexPointer.addUnsignedBytesPointer(4, true, false)); // +4 // TODO ?
		vao.setVertexAttribute(0, 2, VertexPointer.addUnsignedBytesPointer(4, true, true)); // +4 // TODO probably normal index and Iris block ID
		
		try
		{
			vao.completeAndCheck(vertexByteCount);
		}
		catch (RuntimeException e)
		{
			System.out.println(LodUtil.LOD_VERTEX_FORMAT);
			throw e;
		}
		
		if (earthRadiusUniform != -1) setUniform(earthRadiusUniform,
				/*6371KM*/ 6371000.0f / Config.Client.Advanced.Graphics.AdvancedGraphics.earthCurveRatio.get());
		
		
		// Noise Uniforms
		setUniform(noiseEnabledUniform, Config.Client.Advanced.Graphics.NoiseTextureSettings.noiseEnabled.get());
		setUniform(noiseStepsUniform, Config.Client.Advanced.Graphics.NoiseTextureSettings.noiseSteps.get());
		setUniform(noiseIntensityUniform, Config.Client.Advanced.Graphics.NoiseTextureSettings.noiseIntensity.get().floatValue());
		setUniform(noiseDropoffUniform, Config.Client.Advanced.Graphics.NoiseTextureSettings.noiseDropoff.get());
	}
	
	
	
	// Override ShaderProgram.bind()
	public void bind()
	{
		super.bind();
		vao.bind();
	}
	// Override ShaderProgram.unbind()
	public void unbind()
	{
		super.unbind();
		vao.unbind();
	}
	
	// Override ShaderProgram.free()
	public void free()
	{
		vao.free();
		super.free();
	}
	
	public void bindVertexBuffer(int vbo)
	{
		vao.bindBufferToAllBindingPoints(vbo);
	}
	
	public void unbindVertexBuffer()
	{
		vao.unbindBuffersFromAllBindingPoint();
	}
	
	public void fillUniformData(Mat4f combinedMatrix, int lightmapBindPoint, int worldYOffset, float partialTicks)
	{
		super.bind();

		// uniforms
		setUniform(combinedMatUniform, combinedMatrix);
		setUniform(mircoOffsetUniform, 0.01f); // 0.01 block offset
		
		// setUniform(skyLightUniform, skyLight);
		setUniform(lightMapUniform, lightmapBindPoint);
		
		if (worldYOffsetUniform != -1) setUniform(worldYOffsetUniform, (float) worldYOffset);
		
		// Debug
		setUniform(whiteWorldUniform, Config.Client.Advanced.Debugging.enableWhiteWorld.get());
		
		// Fog/Clip Uniforms
		float dhNearClipDistance = RenderUtil.getNearClipPlaneDistanceInBlocks(partialTicks);
		setUniform(clipDistanceUniform, dhNearClipDistance);
	}
	
	public void setModelPos(Vec3f modelPos)
	{
		setUniform(modelOffsetUniform, modelPos);
	}
	
}
