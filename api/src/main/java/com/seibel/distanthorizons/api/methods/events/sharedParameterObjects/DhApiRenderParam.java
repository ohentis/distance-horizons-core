package com.seibel.distanthorizons.api.methods.events.sharedParameterObjects;

import com.seibel.distanthorizons.coreapi.util.math.Mat4f;

/**
 * Contains information relevant to Distant Horizons and Minecraft rendering.
 *
 * @author James Seibel
 * @version 2022-9-5
 * @since API 1.0.0
 */
public class DhApiRenderParam
{
	/** The projection matrix Minecraft is using to render this frame. */
	public final Mat4f mcProjectionMatrix;
	/** The model view matrix Minecraft is using to render this frame. */
	public final Mat4f mcModelViewMatrix;
	
	/** The projection matrix Distant Horizons is using to render this frame. */
	public final Mat4f dhProjectionMatrix;
	/** The model view matrix Distant Horizons is using to render this frame. */
	public final Mat4f dhModelViewMatrix;
	
	/** Indicates how far into this tick the frame is. */
	public final float partialTicks;
	
	
	
	public DhApiRenderParam(
			Mat4f newMcProjectionMatrix, Mat4f newMcModelViewMatrix,
			Mat4f newDhProjectionMatrix, Mat4f newDhModelViewMatrix,
			float newPartialTicks)
	{
		this.mcProjectionMatrix = newMcProjectionMatrix;
		this.mcModelViewMatrix = newMcModelViewMatrix;
		
		this.dhProjectionMatrix = newDhProjectionMatrix;
		this.dhModelViewMatrix = newDhModelViewMatrix;
		
		this.partialTicks = newPartialTicks;
	}
	
}
