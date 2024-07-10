package com.seibel.distanthorizons.api.interfaces.render;

import com.seibel.distanthorizons.api.enums.config.EDhApiLodShading;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3i;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBoxGroupShading;

import java.util.List;
import java.util.function.Consumer;

/**
 * A list of {@link DhApiRenderableBox}'s that
 * can be rendered to DH's terrain pass.
 * 
 * @see DhApiRenderableBox
 * 
 * @author James Seibel
 * @version 2024-6-30
 * @since API 3.0.0
 */
public interface IDhApiRenderableBoxGroup extends List<DhApiRenderableBox>
{
	/** @return the ID for this specific group */
	long getId();
	
	/** Sets whether this group should render or not. */
	void setActive(boolean active);
	/** @return if active this group will render. */
	boolean isActive();
	
	/** Sets whether this group should render with Screen Space Ambient Occlusioning. */
	void setSsaoEnabled(boolean ssaoEnabled);
	/** @return if active this group will render with Screen Space Ambient Occlusioning. */
	boolean isSsaoEnabled();
	
	/** Sets where this group will render in the level. */
	void setOriginBlockPos(DhApiVec3d pos);
	/** @return the block position in the level that all {@see DhApiRenderableBox} will render relative to. */
	DhApiVec3d getOriginBlockPos();
	
	/** 
	 * Called right before this group is rendered. <br>
	 * This is a good place to change the origin or notify of any box changes. 
	 */
	void setPreRenderFunc(Consumer<DhApiRenderParam> renderEventParam);
	void setPostRenderFunc(Consumer<DhApiRenderParam> renderEventParam); // TODO name?
	
	/**
	 * If a cube's color, position, or other property is changed this method
	 * must be called for those changes to render. <br><br>
	 * 
	 * Note: changing the group's position via {@link #setOriginBlockPos} doesn't
	 * require calling this method.
	 */
	void triggerBoxChange();
	
	/** Only accepts values between 0 and 15 */
	void setSkyLight(int skyLight);
	int getSkyLight();
	
	/** Only accepts values between 0 and 15 */
	void setBlockLight(int blockLight);
	int getBlockLight();
	
	void setShading(DhApiRenderableBoxGroupShading shading);
	DhApiRenderableBoxGroupShading getShading();
	
}
