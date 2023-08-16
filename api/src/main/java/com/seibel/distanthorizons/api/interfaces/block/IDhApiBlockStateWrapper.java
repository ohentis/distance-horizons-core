package com.seibel.distanthorizons.api.interfaces.block;

import com.seibel.distanthorizons.api.interfaces.IDhApiUnsafeWrapper;

/**
 * A Minecraft version independent way of handling Blocks.
 *
 * @author James Seibel
 * @version 2023-6-11
 */
public interface IDhApiBlockStateWrapper extends IDhApiUnsafeWrapper
{
	boolean isAir();
	
	boolean isSolid();
	boolean isLiquid();
	
	// TODO:
	//    boolean hasNoCollision();
	//    boolean noFaceIsFullFace();
}
