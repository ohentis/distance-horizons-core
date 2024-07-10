package com.seibel.distanthorizons.api.interfaces.render;

import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;

import java.util.List;

/**
 * Handles creating
 * {@link IDhApiRenderableBoxGroup} objects,
 * which can be added via a {@link IDhApiCustomRenderRegister}.
 *
 * @see IDhApiCustomRenderRegister
 * @see IDhApiRenderableBoxGroup
 * 
 * @author James Seibel
 * @version 2024-7-3
 * @since API 3.0.0
 */
public interface IDhApiCustomRenderObjectFactory
{
	IDhApiRenderableBoxGroup createForSingleBox(DhApiRenderableBox cube);
	IDhApiRenderableBoxGroup createRelativePositionedGroup(DhApiVec3d originBlockPos, List<DhApiRenderableBox> cubeList);
	IDhApiRenderableBoxGroup createAbsolutePositionedGroup(List<DhApiRenderableBox> cubeList);
	
}
