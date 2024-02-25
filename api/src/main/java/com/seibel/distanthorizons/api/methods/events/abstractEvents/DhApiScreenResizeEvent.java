/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2023 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.api.methods.events.abstractEvents;

import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;

/**
 * Called before Distant Horizons starts rendering a buffer. <br>
 * This event cannot be cancelled, use {@link DhApiBeforeRenderEvent} if you want to cancel rendering.
 * 
 * @author James Seibel
 * @version 2023-1-23
 * @since API 1.1.0
 */
public abstract class DhApiScreenResizeEvent implements IDhApiEvent<DhApiScreenResizeEvent.EventParam>
{
	/** Fired immediately before Distant Horizons handles the screen resize. */
	public abstract void onResize(DhApiEventParam<EventParam> event);
	
	
	//=========================//
	// internal DH API methods //
	//=========================//
	
	@Override
	public final void fireEvent(DhApiEventParam<EventParam> event) { this.onResize(event); }
	
	
	//==================//
	// parameter object //
	//==================//
	
	public static class EventParam
	{
		/** Measured in pixels */
		public final int previousWidth;
		/** Measured in pixels */
		public final int previousHeight;
		
		/** Measured in pixels */
		public final int newWidth;
		/** Measured in pixels */
		public final int newHeight;
		
		
		public EventParam(
				int previousWidth, int previousHeight,
				int newWidth, int newHeight)
		{
			this.previousWidth = previousWidth;
			this.previousHeight = previousHeight;
			
			this.newWidth = newWidth;
			this.newHeight = newHeight;
			
		}
	}
	
}