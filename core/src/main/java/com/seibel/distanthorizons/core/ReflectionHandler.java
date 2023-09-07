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

package com.seibel.distanthorizons.core;

import java.lang.invoke.MethodHandles;

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.AbstractOptifineAccessor;
import org.apache.logging.log4j.Logger;

/**
 * A singleton used to determine if a class is present or
 * access variables from methods where they are private
 * or potentially absent. <br><br>
 *
 * For example: presence/absence of Optifine.
 *
 * @author James Seibel
 * @version 2022-11-24
 */
public class ReflectionHandler implements IReflectionHandler
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger(MethodHandles.lookup().lookupClass().getSimpleName());
	
	public static final ReflectionHandler INSTANCE = new ReflectionHandler();
	
	// populated when the methods are called the first time
	private Boolean sodiumPresent = null;
	private Boolean optifinePresent = false;
	
	
	
	private ReflectionHandler() { }
	
	
	
	//===================//
	// is [mod] present? //
	//===================//
	
	@Override
	public boolean optifinePresent()
	{
		if (this.optifinePresent == null)
		{
			// call the base accessor so we don't have duplicate code
			this.optifinePresent = AbstractOptifineAccessor.isOptifinePresent();
		}
		return this.optifinePresent;
	}
	
	@Override
	public boolean sodiumPresent()
	{
		if (this.sodiumPresent == null)
		{
			try
			{
				Class.forName("me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer");
				this.sodiumPresent = true;
			}
			catch (ClassNotFoundException e)
			{
				this.sodiumPresent = false;
			}
		}
		
		return this.sodiumPresent;
	}
	
}
