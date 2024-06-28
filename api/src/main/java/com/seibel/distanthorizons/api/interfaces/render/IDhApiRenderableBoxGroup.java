package com.seibel.distanthorizons.api.interfaces.render;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;

import java.util.List;
import java.util.function.Consumer;

public interface IDhApiRenderableBoxGroup extends List<DhApiRenderableBox>
{
	
	
	long getId();
	void setOriginBlockPos(float x, float y, float z);
	float getOriginBlockX();
	float getOriginBlockY();
	float getOriginBlockZ();
	
	void setPreRenderFunc(Consumer<DhApiRenderParam> renderEventParam);
	
}
