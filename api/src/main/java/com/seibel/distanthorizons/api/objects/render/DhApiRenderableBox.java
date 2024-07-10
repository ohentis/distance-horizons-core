package com.seibel.distanthorizons.api.objects.render;


import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;

import java.awt.*;

/**
 * @see IDhApiRenderableBoxGroup
 * 
 * @author James Seibel
 * @version 2024-6-30
 * @since API 3.0.0
 */
public class DhApiRenderableBox
{
	/** the position closest to (-inf,-inf) */
	public DhApiVec3d minPos;
	/** the position closest to (+inf,+inf) */
	public DhApiVec3d maxPos;
	
	public Color color;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public DhApiRenderableBox(DhApiVec3d minPos, float width, Color color)
	{
		this(minPos, new DhApiVec3d(
				minPos.x + width,
				minPos.y + width,
				minPos.z + width
		), color);
	}
	
	public DhApiRenderableBox(DhApiVec3d minPos, DhApiVec3d maxPos, Color color)
	{
		this.minPos = minPos;
		this.maxPos = maxPos;
		this.color = color;
	}
	
}
	
