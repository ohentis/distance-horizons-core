package com.seibel.distanthorizons.core.config.eventHandlers.presets;

import com.seibel.distanthorizons.core.config.ConfigEntryWithPresetOptions;
import com.seibel.distanthorizons.core.config.listeners.IConfigListener;
import com.seibel.distanthorizons.coreapi.interfaces.config.IConfigEntry;
import com.seibel.distanthorizons.coreapi.util.StringUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public abstract class AbstractPresetConfigEventHandler<TPresetEnum extends Enum<?>> implements IConfigListener
{
	private static final Logger LOGGER = LogManager.getLogger();
	private static final long MS_DELAY_BEFORE_APPLYING_PRESET = 1_000;
	
	protected final ArrayList<ConfigEntryWithPresetOptions<TPresetEnum, ?>> configList = new ArrayList<>();
	/** this timer is used so each preset isn't applied while a user is clicking through the config options */
	protected Timer presetApplicationTimer;
	
	protected boolean changingPreset = false;
	
	
	
	/** 
	 * Set the UI only config based on what is set in the file. <br> 
	 * This should only be called once. 
	 */
	public void setUiOnlyConfigValues()
	{
		TPresetEnum currentQualitySetting = this.getCurrentQualityPreset();
		this.getPresetConfigEntry().set(currentQualitySetting);
	}
	
	
	//===========//
	// listeners //
	//===========//
	
	@Override 
	public void onConfigValueSet()
	{
		TPresetEnum presetEnum = this.getPresetConfigEntry().get();
		
		// if the quick value is custom, nothing needs to be changed
		if (presetEnum == this.getCustomPresetEnum())
		{
			return;
		}
		
		
		// stop the previous timer if one exists
		if (this.presetApplicationTimer != null)
		{
			this.presetApplicationTimer.cancel();
		}
		
		// reset the timer
		TimerTask task = new TimerTask() { public void run() { AbstractPresetConfigEventHandler.this.applyPreset(presetEnum); } };
		this.presetApplicationTimer = new Timer("PresetApplicationTimer");
		this.presetApplicationTimer.schedule(task, MS_DELAY_BEFORE_APPLYING_PRESET);
		
	}
	private void applyPreset(TPresetEnum presetEnum)
	{
		LOGGER.debug("changing preset to: " + presetEnum);
		this.changingPreset = true;
		
		for (ConfigEntryWithPresetOptions<TPresetEnum, ?> configEntry : this.configList)
		{
			configEntry.updateConfigEntry(presetEnum);
		}
		
		this.changingPreset = false;
		LOGGER.debug("preset active: "+presetEnum);
	}
	
	
	@Override
	public void onUiModify() { /* do nothing, we only care about modified config values */ }
	
	/**
	 * listen for changed graphics settings and set the
	 * quick quality to "custom" if anything was changed
	 */
	public void onConfigValueChanged()
	{
		if (this.changingPreset)
		{
			// if a preset is currently being applied, ignore all changes
			return;
		}
		
		
		TPresetEnum newPreset = this.getCurrentQualityPreset();
		TPresetEnum currentPreset = this.getPresetConfigEntry().get();
		
		if (newPreset != currentPreset)
		{
			this.getPresetConfigEntry().set(this.getCustomPresetEnum());
		}
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	/** @return what {@link TPresetEnum} is currently viable based on the {@link AbstractPresetConfigEventHandler#configList}. */
	public TPresetEnum getCurrentQualityPreset()
	{
		// get all quick options
		HashSet<TPresetEnum> possiblePresetSet = new HashSet<>(this.getPresetEnumList());
		
		
		// remove any quick options that aren't possible with the currently selected options
		for (ConfigEntryWithPresetOptions<TPresetEnum, ?> configEntry : this.configList)
		{
			HashSet<TPresetEnum> optionPresetSet = configEntry.getPossibleQualitiesFromCurrentOptionValue();
			possiblePresetSet.retainAll(optionPresetSet);
		}
		
		
		
		ArrayList<TPresetEnum> possiblePrestList = new ArrayList<>(possiblePresetSet);
		if (possiblePrestList.size() > 1)
		{
			// we shouldn't have multiple options, but just in case
			LOGGER.warn("Multiple potential preset options ["+StringUtil.join(", ", possiblePrestList)+"], defaulting to the first one.");
		}
		
		if (possiblePrestList.size() == 0)
		{
			// if no options are valid, return "CUSTOM"
			possiblePrestList.add(this.getCustomPresetEnum());
		}
		
		return possiblePrestList.get(0);
	}
	
	
	
	//==================//
	// abstract methods //
	//==================//
	
	protected abstract IConfigEntry<TPresetEnum> getPresetConfigEntry();
	
	protected abstract List<TPresetEnum> getPresetEnumList();
	protected abstract TPresetEnum getCustomPresetEnum();
	
	
}
