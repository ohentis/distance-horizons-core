package com.seibel.distanthorizons.api.methods.events.abstractEvents;

import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiCancelableEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;

/**
 * Called before Distant Horizons starts rendering a frame. <br>
 * Canceling the event will prevent DH from rendering that frame.
 *
 * @author James Seibel
 * @version 2023-6-23
 * @since API 1.0.0
 */
public abstract class DhApiBeforeRenderEvent implements IDhApiCancelableEvent<DhApiBeforeRenderEvent.EventParam>
{
	/** Fired before Distant Horizons renders LODs. */
	public abstract void beforeRender(DhApiCancelableEventParam<EventParam> input);
	
	
	//=========================//
	// internal DH API methods //
	//=========================//
	
	@Override
	public final void fireEvent(DhApiCancelableEventParam<EventParam> input) { this.beforeRender(input); }
	
	
	//==================//
	// parameter object //
	//==================//
	
	public static class EventParam extends DhApiRenderParam
	{
		public EventParam(DhApiRenderParam parent)
		{
			super(parent.mcProjectionMatrix, parent.mcModelViewMatrix, parent.dhProjectionMatrix, parent.dhModelViewMatrix, parent.partialTicks);
		}
		
	}
	
}