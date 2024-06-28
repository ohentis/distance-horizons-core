package com.seibel.distanthorizons.api.objects.render;


import com.seibel.distanthorizons.coreapi.util.math.Vec3f;

import java.awt.*;

public final class DhApiRenderableBox
{
	public Vec3f minPos;
	public Vec3f maxPos;
	public Color color;
	
	public boolean fullBright = false;
	
	
	
	public DhApiRenderableBox(Vec3f minPos, Vec3f maxPos, Color color)
	{
		this.minPos = minPos;
		this.maxPos = maxPos;
		this.color = color;
	}
	
}
	
