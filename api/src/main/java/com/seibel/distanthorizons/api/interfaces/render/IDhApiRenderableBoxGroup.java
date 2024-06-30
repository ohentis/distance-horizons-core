package com.seibel.distanthorizons.api.interfaces.render;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;

import java.util.List;
import java.util.function.Consumer;

public interface IDhApiRenderableBoxGroup extends List<DhApiRenderableBox>
{
	
	
	long getId();
	void setOriginBlockPos(DhApiVec3f pos);
	DhApiVec3f getOriginBlockPos();
	
	void setPreRenderFunc(Consumer<DhApiRenderParam> renderEventParam);
	
}
