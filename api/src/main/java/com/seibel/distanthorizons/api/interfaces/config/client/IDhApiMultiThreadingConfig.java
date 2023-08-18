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

package com.seibel.distanthorizons.api.interfaces.config.client;

import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigGroup;

/**
 * Distant Horizons' threading configuration.
 *
 * @author James Seibel
 * @version 2023-6-14
 * @since API 1.0.0
 */
public interface IDhApiMultiThreadingConfig extends IDhApiConfigGroup
{
	
	/**
	 * Defines how many world generator threads are used to generate
	 * terrain outside Minecraft's vanilla render distance. <br>
	 * <br>
	 * If the number of threads is less than 1 it will be treated as a percentage
	 * representing how often the single thread will actively generate terrain.
	 */
	IDhApiConfigValue<Integer> worldGeneratorThreads();
	
	/** Defines how many buffer (GPU Terrain data) builder threads are used. */
	IDhApiConfigValue<Integer> bufferBuilderThreads();
	
	/** Defines how many file handler threads are used. */
	IDhApiConfigValue<Integer> fileHandlerThreads();
	
	/**
	 * Defines how many Full to Render data converter threads are used. <br><br>
	 *
	 * <strong>Full data</strong> - Distant Horizons data based on BlockState and Biome IDs <br>
	 * <strong>Render data</strong> - color data used when Distant Horizons is rendering
	 */
	IDhApiConfigValue<Integer> dataConverterThreads();
	
	/** Defines how many threads should be used to convert Minecraft chunks to LOD data. */
	IDhApiConfigValue<Integer> chunkLodConverterThreads();
	
}
