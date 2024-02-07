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
 * MINECRAFT <br>
 * OLD_LIGHTING <br>
 * NONE <br>
 *
 * @since API 1.0.0
 */
public enum ELodShading
{
	// Reminder:
	// when adding items up the API minor version
	// when removing items up the API major version
	
	/** Uses Minecraft's shading for LODs */
	MINECRAFT,
	/** 
	 * Simulates Minecraft's shading. 
	 * This is most useful for shaders that disable Minecraft's shading
	 * but still require shading on LODs.
	 */
	OLD_LIGHTING,
	/** LODs will have no shading */
	NONE;
	
}
