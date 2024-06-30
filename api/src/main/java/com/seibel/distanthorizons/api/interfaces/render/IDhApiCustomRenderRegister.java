package com.seibel.distanthorizons.api.interfaces.render;

import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;

import java.util.List;

/**
 * Handles adding, removing, and creating
 * {@link IDhApiRenderableBoxGroup} objects,
 * which can be used to render custom objects into
 * DH's terrain.
 *
 * @author James Seibel
 * @version 2024-6-30
 * @since API 3.0.0
 */
public interface IDhApiCustomRenderRegister
{
	void add(IDhApiRenderableBoxGroup cubeGroup) throws IllegalArgumentException;
	
	IDhApiRenderableBoxGroup remove(long id);
	
	
	IDhApiRenderableBoxGroup createForSingleBox(DhApiRenderableBox cube);
	IDhApiRenderableBoxGroup createRelativePositionedGroup(DhApiVec3f originBlockPos, List<DhApiRenderableBox> cubeList);
	IDhApiRenderableBoxGroup createAbsolutePositionedGroup(List<DhApiRenderableBox> cubeList);
	
}
