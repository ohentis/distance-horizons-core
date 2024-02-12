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

package com.seibel.distanthorizons.core.config.eventHandlers;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EHorizontalQuality;
import com.seibel.distanthorizons.api.enums.config.EMaxHorizontalResolution;
import com.seibel.distanthorizons.api.enums.config.EVerticalQuality;
import com.seibel.distanthorizons.api.enums.config.quickOptions.EQualityPreset;
import com.seibel.distanthorizons.api.enums.rendering.ETransparency;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.ConfigEntryWithPresetOptions;
import com.seibel.distanthorizons.core.config.eventHandlers.presets.AbstractPresetConfigEventHandler;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.config.listeners.IConfigListener;
import com.seibel.distanthorizons.coreapi.interfaces.config.IConfigEntry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class DebugColumnConfigEventHandler implements IConfigListener
{
	public static DebugColumnConfigEventHandler INSTANCE = new DebugColumnConfigEventHandler();
	
	@Override
	public void onConfigValueSet()
	{
		IDhApiRenderProxy renderProxy = DhApi.Delayed.renderProxy;
		if (renderProxy != null)
		{
			renderProxy.clearRenderDataCache();
		}
	}
	
}
