package com.seibel.distanthorizons.core.wrapperInterfaces.block;

import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;

/** A Minecraft version independent way of handling Blocks. */
public interface IBlockStateWrapper extends IDhApiBlockStateWrapper
{
    String serialize();
	
	/** 
	 * Returning a value of 0 means the block is completely transparent. <br. 
	 * Returning a value of 15 means the block is completely opaque.
	 */
	int getOpacity();
	
	int getLightEmission();
	
}
