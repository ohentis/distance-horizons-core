package com.seibel.distanthorizons.core.render.renderer.shaders;

import com.seibel.distanthorizons.core.render.glObject.shader.ShaderProgram;

public class DarkShader extends AbstractShaderRenderer
{
	public static DarkShader INSTANCE = new DarkShader();
	
	protected DarkShader()
	{
		super(new ShaderProgram("shaders/normal.vert", "shaders/test/dark.frag", "fragColor", new String[]{"vPosition", "color"}));
	}
	
}
