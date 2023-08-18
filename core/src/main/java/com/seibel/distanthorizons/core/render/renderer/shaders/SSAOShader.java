package com.seibel.distanthorizons.core.render.renderer.shaders;

import com.seibel.distanthorizons.core.render.glObject.shader.ShaderProgram;
import com.seibel.distanthorizons.core.render.glObject.vertexAttribute.VertexAttribute;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.coreapi.util.math.Mat4f;
import org.lwjgl.opengl.GL32;

public class SSAOShader extends AbstractShaderRenderer
{
	public static SSAOShader INSTANCE = new SSAOShader();
	
	private static final int MAX_KERNEL_SIZE = 32;
	private float[] kernel = new float[MAX_KERNEL_SIZE * 3];
	
	
	public SSAOShader()
	{
		super(
				new ShaderProgram("shaders/normal.vert", "shaders/ssao/ao.frag",
						"fragColor", new String[]{"vPosition"}),
				new ShaderProgram("shaders/normal.vert", "shaders/ssao/apply-frag.frag",
						"fragColor", new String[]{"vPosition"})
		);
		
	}
	@Override
	void postInit()
	{
		// Generate kernel
		kernel = genKernel();
	}
	
	@Override
	void setVertexAttributes()
	{
		va.setVertexAttribute(0, 0, VertexAttribute.VertexPointer.addVec2Pointer(false));
	}
	
	@Override
	void setShaderUniforms(float partialTicks)
	{
		Mat4f perspective = Mat4f.perspective(
				(float) MC_RENDER.getFov(partialTicks),
				MC_RENDER.getTargetFrameBufferViewportWidth() / (float) MC_RENDER.getTargetFrameBufferViewportHeight(),
				RenderUtil.getNearClipPlaneDistanceInBlocks(partialTicks),
				(float) ((RenderUtil.getFarClipPlaneDistanceInBlocks() + LodUtil.REGION_WIDTH) * Math.sqrt(2)));

		this.shader.setUniform(this.shader.getUniformLocation("gProj"), perspective);
		this.shader.setUniform(this.shader.getUniformLocation("gSampleRad"), 3.0f);
		this.shader.setUniform(this.shader.getUniformLocation("gFactor"), 0.8f);
		this.shader.setUniform(this.shader.getUniformLocation("gPower"), 1.0f);
		
		GL32.glUniform3fv(this.shader.getUniformLocation("gKernel"), kernel);
		GL32.glUniform1i(this.shader.getUniformLocation("gDepthMap"), 0);
	}
	
	private static float[] genKernel()
	{
		float[] kernel = new float[MAX_KERNEL_SIZE * 3];
		for (int i = 0; i < MAX_KERNEL_SIZE; i++)
		{
			float sampleX = (float) (Math.random() * 2.0 - 1.0);
			float sampleY = (float) (Math.random() * 2.0 - 1.0);
			float sampleZ = (float) Math.random();
			
			
			// Normalize
			float magnitude = (float) Math.sqrt(Math.pow(sampleX, 2) + Math.pow(sampleY, 2) + Math.pow(sampleZ, 2));
			sampleX /= magnitude;
			sampleY /= magnitude;
			sampleZ /= magnitude;
			
			float scale = i / (float) MAX_KERNEL_SIZE;
			float interpolatedScale = (float) (0.1 + (scale * scale) * (0.9));
			
			sampleX *= interpolatedScale;
			sampleY *= interpolatedScale;
			sampleZ *= interpolatedScale;
			kernel[i * 3] = sampleX;
			kernel[i * 3 + 1] = sampleY;
			kernel[i * 3 + 2] = sampleZ;
		}
		return kernel;
	}
	
}
