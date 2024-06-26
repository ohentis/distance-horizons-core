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

package com.seibel.distanthorizons.core.world;

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import org.apache.logging.log4j.Logger;

import java.io.Closeable;

/**
 * Represents an entire world (aka server) and
 * contains every level in that world.
 */
public abstract class AbstractDhWorld implements IDhWorld, Closeable
{
	protected static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	public final EWorldEnvironment environment;
	
	
	
	// constructor //
	
	protected AbstractDhWorld(EWorldEnvironment environment) { this.environment = environment; }
	
	
	
	// abstract methods //
	
	// removes the "throws IOException"
	@Override
	public abstract void close();
	
	
	
	// helper methods //
	
	public String GetDebugMenuString() { return this.environment + " World with " + F3Screen.NUMBER_FORMAT.format(this.getLoadedLevelCount()) + " levels"; }
	
}
