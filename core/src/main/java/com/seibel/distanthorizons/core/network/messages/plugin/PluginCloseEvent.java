package com.seibel.distanthorizons.core.network.messages.plugin;

import com.seibel.distanthorizons.core.network.messages.ICloseEvent;
import com.seibel.distanthorizons.core.network.plugin.PluginChannelMessage;

/**
 * This is not a "real" message, and only used to indicate a disconnection.
 */
public class PluginCloseEvent extends PluginChannelMessage implements ICloseEvent
{
}
