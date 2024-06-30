package com.seibel.distanthorizons.api.interfaces.render;

import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;

import java.util.List;

public interface IDhApiCustomRenderRegister
{
	void add(IDhApiRenderableBoxGroup cubeGroup) throws IllegalArgumentException;
	
	IDhApiRenderableBoxGroup remove(long id);
	
	
	IDhApiRenderableBoxGroup createForSingleBox(DhApiRenderableBox cube);
	IDhApiRenderableBoxGroup createRelativePositionedGroup(DhApiVec3f originBlockPos, List<DhApiRenderableBox> cubeList);
	IDhApiRenderableBoxGroup createAbsolutePositionedGroup(List<DhApiRenderableBox> cubeList);
	
}
