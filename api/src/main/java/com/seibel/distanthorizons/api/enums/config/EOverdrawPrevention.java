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

package com.seibel.distanthorizons.api.enums.config;

/**
 * NONE <br>
 * LIGHT <br>
 * MEDIUM <br>
 * HEAVY <br>
 * 
 * CUSTOM <br>
 *
 * @since API 1.0.0
 * @deprecated will be removed when DH updates to MC 1.21 <br>
 *              After removal a float value will be used to control overdraw instead.
 */
@Deprecated
public enum EOverdrawPrevention
{
	// Reminder:
	// when adding items up the API minor version
	// when removing items up the API major version
	
	NONE,
	LIGHT,
	MEDIUM,
	HEAVY,
	
	/** 
	 * Should not be passed in. <br>
	 * Is returned if the overdraw value doesn't match any of the enums defined here.
	 */
	CUSTOM;
}
