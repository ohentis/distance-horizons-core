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

package com.seibel.distanthorizons.core.dataObjects.fullData.accessor;

import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;

/**
 * Contains Full Data points and basic methods for getting and setting them. <br>
 * Can be used standalone or as the base for Full data sources.
 *
 * @see CompleteFullDataSource
 */
@Deprecated // TODO merge into FullDataSourceV1
public class FullDataArrayAccessor
{
	protected final FullDataPointIdMap mapping;
	
	/** A flattened 2D array (for the X and Z directions) containing an array for the Y direction. */
	protected final long[][] dataArrays;
	
	/** measured in data points */
	protected final int width;
	/** measured in data points */
	protected final int dataWidth;
	
	/** index offset used when getting/setting data in {@link FullDataArrayAccessor#dataArrays}. */
	protected final int offset;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public FullDataArrayAccessor(FullDataPointIdMap mapping, long[][] dataArrays, int width)
	{
		if (dataArrays.length != width * width)
		{
			throw new IllegalArgumentException("tried constructing dataArrayView with invalid input!");
		}
		
		this.dataArrays = dataArrays;
		this.width = width;
		this.dataWidth = width;
		this.mapping = mapping;
		this.offset = 0;
	}
	
	public FullDataArrayAccessor(FullDataArrayAccessor source, int width, int offsetX, int offsetZ)
	{
		if (source.width < width || source.width < width + offsetX || source.width < width + offsetZ)
		{
			throw new IllegalArgumentException("tried constructing dataArrayView subview with invalid input!");
		}
		
		this.dataArrays = source.dataArrays;
		this.width = width;
		this.dataWidth = source.dataWidth;
		this.mapping = source.mapping;
		this.offset = source.offset + offsetX * this.dataWidth + offsetZ;
	}
	
	
	
	//=========//
	// getters //
	//=========//
	
	public FullDataPointIdMap getMapping() { return this.mapping; }
	
	public long[] get(int index) { return this.get(index / this.width, index % this.width); }
	public long[] get(int relativeX, int relativeZ)
	{
		int dataArrayIndex = (relativeX * this.width) + relativeZ + this.offset;
		if (dataArrayIndex >= this.dataArrays.length)
		{
			LodUtil.assertNotReach(
					"FullDataArrayAccessor.get() called with a relative position that is outside the data source. \n" +
							"source width: [" + this.width + "] source offset: [" + this.offset + "]\n" +
							"given relative pos X: [" + relativeX + "] Z: [" + relativeZ + "]\n" +
							"dataArrays.length: [" + this.dataArrays.length + "] dataArrayIndex: [" + dataArrayIndex + "].");
		}
		
		return this.dataArrays[dataArrayIndex];
	}
	
}
