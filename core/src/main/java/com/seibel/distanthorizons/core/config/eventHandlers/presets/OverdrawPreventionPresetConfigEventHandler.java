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

package com.seibel.distanthorizons.core.config.eventHandlers.presets;

import com.seibel.distanthorizons.api.enums.config.EMaxHorizontalResolution;
import com.seibel.distanthorizons.api.enums.config.EOverdrawPrevention;
import com.seibel.distanthorizons.api.enums.config.quickOptions.EQualityPreset;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.ConfigEntryWithPresetOptions;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.coreapi.interfaces.config.IConfigEntry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class OverdrawPreventionPresetConfigEventHandler extends AbstractPresetConfigEventHandler<EOverdrawPrevention>
{
	public static final OverdrawPreventionPresetConfigEventHandler INSTANCE = new OverdrawPreventionPresetConfigEventHandler();
	
	private static final Logger LOGGER = LogManager.getLogger();
	
	
	private final ConfigEntryWithPresetOptions<EOverdrawPrevention, Double> overdrawPrevention = new ConfigEntryWithPresetOptions<>(Config.Client.Advanced.Graphics.AdvancedGraphics.overdrawPrevention,
			new HashMap<EOverdrawPrevention, Double>()
			{{
				this.put(EOverdrawPrevention.HEAVY, 0.6);
				this.put(EOverdrawPrevention.MEDIUM, 0.4);
				this.put(EOverdrawPrevention.LIGHT, 0.25);
				this.put(EOverdrawPrevention.NONE, 0.0);
			}});
		
	
	
	//==============//
	// constructors //
	//==============//
	
	/** private since we only ever need one handler at a time */
	private OverdrawPreventionPresetConfigEventHandler()
	{
		// add each config used by this preset
		this.configList.add(this.overdrawPrevention);
		
		
		for (ConfigEntryWithPresetOptions<EOverdrawPrevention, ?> config : this.configList)
		{
			// ignore try-using, the listener should only ever be added once and should never be removed
			new ConfigChangeListener<>(config.configEntry, (val) -> { this.onConfigValueChanged(); });
		}
	}
	
	
	
	//==============//
	// enum getters //
	//==============//
	
	@Override
	protected IConfigEntry<EOverdrawPrevention> getPresetConfigEntry() { return Config.Client.Advanced.Graphics.AdvancedGraphics.overdrawPreventionPreset; }
	
	@Override
	protected List<EOverdrawPrevention> getPresetEnumList() { return Arrays.asList(EOverdrawPrevention.values()); }
	@Override
	protected EOverdrawPrevention getCustomPresetEnum() { return EOverdrawPrevention.CUSTOM; }
	
}
