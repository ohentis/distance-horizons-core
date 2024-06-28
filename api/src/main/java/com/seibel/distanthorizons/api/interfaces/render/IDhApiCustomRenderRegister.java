package com.seibel.distanthorizons.api.interfaces.render;

import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;

import java.util.List;

public interface IDhApiCustomRenderRegister
{
	void add(IDhApiRenderableBoxGroup cubeGroup) throws IllegalArgumentException;
	
	IDhApiRenderableBoxGroup remove(long id);
	
	
	IDhApiRenderableBoxGroup createForSingleBox(DhApiRenderableBox cube);
	IDhApiRenderableBoxGroup createRelativePositionedGroup(float originBlockX, float originBlockY, float originBlockZ, List<DhApiRenderableBox> cubeList);
	IDhApiRenderableBoxGroup createAbsolutePositionedGroup(List<DhApiRenderableBox> cubeList);
	
}
