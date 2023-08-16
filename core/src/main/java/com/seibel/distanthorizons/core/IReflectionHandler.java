/*
 *    This file is part of the Distant Horizons mod (formerly the LOD Mod),
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2022  James Seibel
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

import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;

/**
 * A singleton used to get variables from methods
 * where they are private or potentially absent.
 * Specifically the fog setting used by Optifine or the
 * presence/absence of other mods.
 * <p>
 * This interface doesn't necessarily have to exist, but
 * it makes using the singleton handler more uniform (always
 * passing in interfaces), and it may be needed in the future if
 * we find that reflection handlers need to be different for
 * different MC versions.
 *
 * @author James Seibel
 * @version 2022-11-24
 */
public interface IReflectionHandler extends IBindable
{
	/** @return if Sodium (or a sodium like) mod is present. */
	boolean sodiumPresent();
	
	boolean optifinePresent();
	
}
