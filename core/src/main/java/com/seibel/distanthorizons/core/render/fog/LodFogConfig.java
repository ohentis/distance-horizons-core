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

package com.seibel.distanthorizons.core.render.fog;

import com.seibel.distanthorizons.api.enums.rendering.*;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.render.glObject.GLProxy;
import com.seibel.distanthorizons.core.render.glObject.shader.Shader;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IOptifineAccessor;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * This holds fog related settings and
 * creates the fog related shader code.
 *
 * @author Leetom
 * @author James Seibel
 * @version 2022-11-24
 */
// TODO: Move lots out of here, there should be a listener hooked onto the config to update the shader
public class LodFogConfig
{
	private static final IOptifineAccessor OPTIFINE = ModAccessorInjector.INSTANCE.get(IOptifineAccessor.class);
	
	public static final boolean DEBUG_DUMP_GENERATED_CODE = false;
	
	public final FogSettings farFogSetting;
	public final FogSettings heightFogSetting;
	public final EDhApiHeightFogMixMode heightFogMixMode;
	public final EDhApiHeightFogMode heightFogMode;
	public final float heightFogHeight;
	
	// TODO: Move these out of here
	public final int earthCurveRatio;
	
	// Noise Values
	public final boolean noiseEnable;
	public final int noiseSteps;
	public final float noiseIntensity;
	public final int noiseDropoff;
	
	
	public static LodFogConfig generateFogConfig() { return new LodFogConfig(); }
	
	/** sets all fog options from the config */
	private LodFogConfig()
	{
		// TODO: Move these out of here
		this.earthCurveRatio = Config.Client.Advanced.Graphics.Experimental.earthCurveRatio.get();
		
		this.noiseEnable = Config.Client.Advanced.Graphics.NoiseTexture.enableNoiseTexture.get();
		this.noiseSteps = Config.Client.Advanced.Graphics.NoiseTexture.noiseSteps.get();
		this.noiseIntensity = Config.Client.Advanced.Graphics.NoiseTexture.noiseIntensity.get().floatValue();
		this.noiseDropoff = Config.Client.Advanced.Graphics.NoiseTexture.noiseDropoff.get();
		
		
		if (Config.Client.Advanced.Graphics.Fog.enableDhFog.get())
		{
			// fog should be drawn
			
			this.farFogSetting = new FogSettings(
					Config.Client.Advanced.Graphics.Fog.farFogStart.get(),
					Config.Client.Advanced.Graphics.Fog.farFogEnd.get(),
					Config.Client.Advanced.Graphics.Fog.farFogMin.get(),
					Config.Client.Advanced.Graphics.Fog.farFogMax.get(),
					Config.Client.Advanced.Graphics.Fog.farFogDensity.get(),
					Config.Client.Advanced.Graphics.Fog.farFogFalloff.get()
			);
			
			this.heightFogMixMode = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogMixMode.get();
			if (this.heightFogMixMode == EDhApiHeightFogMixMode.IGNORE_HEIGHT 
				|| this.heightFogMixMode == EDhApiHeightFogMixMode.BASIC)
			{
				// basic fog mixing
				
				this.heightFogSetting = null;
				this.heightFogMode = null;
				this.heightFogHeight = 0.f;
			}
			else
			{
				// advanced fog mixing
				
				this.heightFogSetting = new FogSettings(
						Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogDensity.get(),
						Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogEnd.get(),
						Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogMin.get(),
						Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogMax.get(),
						Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogDensity.get(),
						Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogFalloff.get()
				);
				
				this.heightFogMode = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogMode.get();
				
				if (this.heightFogMode.basedOnCamera)
				{
					this.heightFogHeight = 0.f;
				}
				else
				{
					this.heightFogHeight = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogBaseHeight.get().floatValue();
				}
			}
		}
		else
		{
			// fog disabled
			
			this.farFogSetting = null;
			this.heightFogMixMode = null;
			this.heightFogMode = null;
			this.heightFogSetting = null;
			this.heightFogHeight = 0.f;
		}
	}
	
	public StringBuilder loadAndProcessFragShader(String path, boolean absoluteFilePath)
	{
		StringBuilder stringBuilder = this.makeRuntimeDefine();
		this.generateRuntimeShaderCode(Shader.loadFile(path, absoluteFilePath, stringBuilder));
		
		if (DEBUG_DUMP_GENERATED_CODE)
		{
			try (FileOutputStream file = new FileOutputStream("debugGenerated.frag", false))
			{
				file.write(stringBuilder.toString().getBytes(StandardCharsets.UTF_8));
				GLProxy.GL_LOGGER.info("Debug dumped generated code to debugGenerated.frag for {}", path);
			}
			catch (IOException e)
			{
				GLProxy.GL_LOGGER.warn("Failed to debug dump generated code to file for {}", path);
			}
		}
		
		return stringBuilder;
	}
	
	/** Generates the necessary constants for a fragment shader */
	private void generateRuntimeShaderCode(StringBuilder str)
	{
		str.append("// =======RUNTIME GENERATED CODE SECTION======== //\n");
		
		// Generate method: float getNearFogThickness(float dist);
		str.append("" +
				"float getNearFogThickness(float dist) \n" +
				"{ \n" +
				"	return linearFog(dist, uNearFogStart, uNearFogLength, 0.0, 1.0); \n" +
				"} \n");
		
		
		if (this.farFogSetting == null)
		{
			str.append("\n" +
					"float getFarFogThickness(float dist) { return 0.0; } \n" +
					"float getHeightFogThickness(float dist) { return 0.0; } \n" +
					"float calculateFarFogDepth(float horizontal, float dist, float uNearFogStart) { return 0.0; } \n" +
					"float calculateHeightFogDepth(float vertical, float realY) { return 0.0; } \n" +
					"float mixFogThickness(float near, float far, float height) \n" +
					"{ \n" +
					"    return 0.0; \n" +
					"} \n\n");
		}
		else
		{
			// Generate method: float getFarFogThickness(float dist);
			str.append("" +
					"float getFarFogThickness(float dist) \n" +
					"{ \n" +
					getFarFogMethod(this.farFogSetting.fogType) + "\n" +
					"} \n");
			
			
			// Generate method: float getHeightFogThickness(float dist);
			str.append("" +
					"float getHeightFogThickness(float dist) \n" +
					"{ \n" +
					(this.heightFogSetting != null ? getHeightFogMethod(this.heightFogSetting.fogType) : "	return 0.0;") + "\n" +
					"} \n");
			
			
			// Generate method: float calculateHeightFogDepth(float vertical, float realY);
			str.append("" +
					"float calculateHeightFogDepth(float vertical, float realY) \n" +
					"{ \n" +
					(this.heightFogSetting != null ? getHeightDepthMethod(this.heightFogMode, this.heightFogHeight) : "	return 0.0;") + "\n" +
					"} \n");
			
			
			// Generate method: calculateFarFogDepth(float horizontal, float dist, float uNearFogStart);
			str.append("" +
					"float calculateFarFogDepth(float horizontal, float dist, float uNearFogStart) \n" +
					"{ \n" +
					"	return " + (this.heightFogMixMode == EDhApiHeightFogMixMode.BASIC ?
					"(dist - uNearFogStart)/(1.0 - uNearFogStart);" :
					"(horizontal - uNearFogStart)/(1.0 - uNearFogStart);") +
					"} \n");
			
			// Generate method: float mixFogThickness(float near, float far, float height);
			str.append("" +
					"float mixFogThickness(float near, float far, float height) \n" +
					"{ \n" +
					getMixFogLine(this.heightFogMixMode) + "\n" +
					"} \n");
		}
	}
	
	
	
	//=================//
	// shader creation //
	// helper methods  //
	//=================//
	
	private StringBuilder makeRuntimeDefine()
	{
		StringBuilder str = new StringBuilder();
		str.append("// =======RUNTIME GENERATED DEFINE SECTION======== //\n");
		str.append("#version 150 core\n");
		
		FogSettings activeFarFogSetting = this.farFogSetting != null ? this.farFogSetting : FogSettings.EMPTY;
		FogSettings activeHeightFogSetting = this.heightFogSetting != null ? this.heightFogSetting : FogSettings.EMPTY;
		
		str.append("\n" +
				"#define farFogStart " + activeFarFogSetting.start + "\n" +
				"#define farFogLength " + (activeFarFogSetting.end - activeFarFogSetting.start) + "\n" +
				"#define farFogMin " + activeFarFogSetting.min + "\n" +
				"#define farFogRange " + (activeFarFogSetting.max - activeFarFogSetting.min) + "\n" +
				"#define farFogDensity " + activeFarFogSetting.density + "\n" +
				"\n" +
				"#define heightFogStart " + activeHeightFogSetting.start + "\n" +
				"#define heightFogLength " + (activeHeightFogSetting.end - activeHeightFogSetting.start) + "\n" +
				"#define heightFogMin " + activeHeightFogSetting.min + "\n" +
				"#define heightFogRange " + (activeHeightFogSetting.max - activeHeightFogSetting.min) + "\n" +
				"#define heightFogDensity " + activeHeightFogSetting.density + "\n" +
				"\n");
		
		str.append("// =======RUNTIME END======== //\n");
		return str;
	}
	
	private static String getFarFogMethod(EDhApiFogFalloff fogType)
	{
		switch (fogType)
		{
			case LINEAR:
				return "return linearFog(dist, farFogStart, farFogLength, farFogMin, farFogRange);\n";
			case EXPONENTIAL:
				return "return exponentialFog(dist, farFogStart, farFogLength, farFogMin, farFogRange, farFogDensity);\n";
			case EXPONENTIAL_SQUARED:
				return "return exponentialSquaredFog(dist, farFogStart, farFogLength, farFogMin, farFogRange, farFogDensity);\n";
			
			default:
				throw new IllegalArgumentException("FogType [" + fogType + "] not implemented for [getFarFogMethod].");
		}
	}
	
	private static String getHeightDepthMethod(EDhApiHeightFogMode heightMode, float heightFogHeight)
	{
		String str = "";
		if (!heightMode.basedOnCamera)
		{
			str = "	vertical = realY - (" + heightFogHeight + ");\n";
		}
		
		if (heightMode.below && heightMode.above)
		{
			str += "	return abs(vertical);\n";
		}
		else if (heightMode.below)
		{
			str += "	return -vertical;\n";
		}
		else if (heightMode.above)
		{
			str += "	return vertical;\n";
		}
		else
		{
			str += "	return 0;\n";
		}
		return str;
	}
	
	/**
	 * Returns the method call for the given fog type. <br>
	 * Example: <br>
	 * <code>"	return linearFog(dist, heightFogStart, heightFogLength, heightFogMin, heightFogRange);"</code>
	 */
	private static String getHeightFogMethod(EDhApiFogFalloff fogType)
	{
		switch (fogType)
		{
			case LINEAR:
				return "	return linearFog(dist, heightFogStart, heightFogLength, heightFogMin, heightFogRange);\n";
			case EXPONENTIAL:
				return "	return exponentialFog(dist, heightFogStart, heightFogLength, heightFogMin, heightFogRange, heightFogDensity);\n";
			case EXPONENTIAL_SQUARED:
				return "	return exponentialSquaredFog(dist, heightFogStart, heightFogLength, heightFogMin, heightFogRange, heightFogDensity);\n";
			
			default:
				throw new IllegalArgumentException("FogType [" + fogType + "] not implemented for [getHeightFogMethod].");
		}
	}
	
	/**
	 * creates a line in the format <br>
	 * <code>"	return max(1.0-near, far);" </code>
	 */
	private static String getMixFogLine(EDhApiHeightFogMixMode heightFogMode)
	{
		String str = "	return ";
		
		switch (heightFogMode)
		{
			case BASIC:
			case IGNORE_HEIGHT:
					str += "near * far;\n";
				break;
			
			case ADDITION:
					str += "near * (far + height);\n";
				break;
			
			case MAX:
					str += "near * max(far, height);\n";
				break;
			
			case INVERSE_MULTIPLY:
					str += "near * (1.0 - (1.0-far)*(1.0-height));\n";
				break;
			
			case MULTIPLY:
					str += "near * far * height;\n";
				break;
			
			case LIMITED_ADDITION:
					str += "near * (far + max(far, height));\n";
				break;
			
			case MULTIPLY_ADDITION:
					str += "near * (far + far*height);\n";
				break;
			
			case INVERSE_MULTIPLY_ADDITION:
					str += "near * (far + 1.0 - (1.0-far)*(1.0-height));\n";
				break;
			
			case AVERAGE:
					str += "near * (far*0.5 + height*0.5);\n";
				break;
			
			default:
				throw new IllegalArgumentException("FogType [" + heightFogMode + "] not implemented for [getMixFogMethod].");
		}
		
		return str;
	}
	
	
	
	
	
	
	//========================//
	// default object methods //
	//========================//
	
	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		else if (other == null || this.getClass() != other.getClass())
		{
			return false;
		}
		else
		{
			LodFogConfig that = (LodFogConfig) other;
			return Float.compare(that.heightFogHeight, this.heightFogHeight) == 0 &&
					Objects.equals(this.farFogSetting, that.farFogSetting) &&
					Objects.equals(this.heightFogSetting, that.heightFogSetting) && this.heightFogMixMode == that.heightFogMixMode &&
					this.heightFogMode == that.heightFogMode
					// TODO: Move these out of here
					&& this.earthCurveRatio == that.earthCurveRatio
					&& this.noiseEnable == that.noiseEnable && this.noiseSteps == that.noiseSteps && this.noiseIntensity == that.noiseIntensity && this.noiseDropoff == that.noiseDropoff;
		}
	}
	
	@Override
	public int hashCode()
	{
		return Objects.hash(this.farFogSetting, this.heightFogSetting, this.heightFogMixMode, this.heightFogMode, this.heightFogHeight, this.earthCurveRatio, this.noiseEnable, this.noiseSteps, this.noiseIntensity, this.noiseDropoff);
	}
	
}
