package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.coreapi.util.math.Mat4f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;

public interface IDhClientLevel extends IDhWorldGenLevel
{
	void clientTick();
	
	void render(Mat4f mcModelViewMatrix, Mat4f mcProjectionMatrix, float partialTicks, IProfilerWrapper profiler);
	
	int computeBaseColor(DhBlockPos pos, IBiomeWrapper biome, IBlockStateWrapper block);
	
	IClientLevelWrapper getClientLevelWrapper();
	
	/**
	 * Re-creates the color, render data.
	 * This method should be called after resource packs are changed or LOD settings are modified.
	 */
	void clearRenderCache();
	
}
