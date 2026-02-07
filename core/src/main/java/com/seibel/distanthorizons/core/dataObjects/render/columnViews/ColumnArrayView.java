/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
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

package com.seibel.distanthorizons.core.dataObjects.render.columnViews;


import com.seibel.distanthorizons.api.enums.config.EDhApiVerticalQuality;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.util.RenderDataPointReducingList;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.Arrays;
import java.util.ConcurrentModificationException;

public final class ColumnArrayView
{
	public final LongArrayList data;
	
	/** 
	 * How many data points are currently being represented by this view. <br>
	 * Will be equal to or less than {@link ColumnArrayView#maxVerticalSliceCount}.
	 */
	public final int size;
	/** 
	 * Vertical size in data points. <Br>
	 * Can be 0 if this column was created for an empty data source.
	 * @see EDhApiVerticalQuality#calculateMaxNumberOfVerticalSlicesAtDetailLevel(byte)
	 */
	public final int maxVerticalSliceCount;
	
	/**
	 * Where the relative starting index is in the {@link ColumnArrayView#data} array
	 * if this view is representing part of a {@link ColumnRenderSource}.
	 */
	public final int offset;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	/** @throws IllegalArgumentException if the offset is greater than the data's size */
	public ColumnArrayView(LongArrayList data, int size, int offset, int maxVerticalSliceCount) throws IllegalArgumentException
	{
		this.data = data;
		this.size = size;
		this.offset = offset;
		this.maxVerticalSliceCount = maxVerticalSliceCount;
		
		if (this.data.size() < this.offset)
		{
			throw new IllegalArgumentException("data size ["+this.data.size()+"] is shorter than offset ["+this.offset+"].");
		}
	}
	
	//endregion
	
	
	
	//=====================//
	// getters and setters //
	//=====================//
	//region
	
	public long get(int index) 
	{
		try
		{
			return this.data.getLong(index + this.offset);
		}
		catch (IndexOutOfBoundsException e)
		{
			// we can fairly confidently say this is a concurrent exception over an actual
			// index out of bounds, since we're generally iterating over the whole
			// array any time we use this getter.
			throw new ConcurrentModificationException("Potential concurrent modification detected. Make sure the parent ColumnRenderSource isn't being closed before the ColumnArrayView processing is complete.", e);
		}
	}
	public void set(int index, long value) { this.data.set(index + this.offset, value); }
	
	/** can be used to determine sub-view starting indexes */
	public int subViewCount() { return (this.maxVerticalSliceCount != 0) ? (this.size / this.maxVerticalSliceCount) : 0; }
	
	public ColumnArrayView subView(int dataIndexStart, int dataCount)
	{ return new ColumnArrayView(this.data, dataCount * this.maxVerticalSliceCount, this.offset + dataIndexStart * this.maxVerticalSliceCount, this.maxVerticalSliceCount); }
	
	public void fill(long value) { Arrays.fill(this.data.elements(), this.offset, this.offset + this.size, value); }
	
	public void copyFrom(ColumnArrayView source) { this.copyFrom(source, 0); }
	public void copyFrom(ColumnArrayView source, int outputDataIndexOffset)
	{
		if (source.maxVerticalSliceCount > this.maxVerticalSliceCount)
		{
			throw new IllegalArgumentException("source verticalSize must be <= self's verticalSize to copy");
		}
		else if (source.subViewCount() + outputDataIndexOffset > this.subViewCount())
		{
			throw new IllegalArgumentException("dataIndexStart + source.dataCount() must be <= self.dataCount() to copy");
		}
		else if (source.maxVerticalSliceCount != this.maxVerticalSliceCount)
		{
			for (int i = 0; i < source.subViewCount(); i++)
			{
				int outputOffset = this.offset + (outputDataIndexOffset * this.maxVerticalSliceCount) + (i * this.maxVerticalSliceCount);
				source.subView(i, 1).copyTo(this.data.elements(), outputOffset, source.maxVerticalSliceCount);
				Arrays.fill(this.data.elements(), outputOffset + source.maxVerticalSliceCount,
						outputOffset + this.maxVerticalSliceCount, 0);
			}
		}
		else
		{
			source.copyTo(this.data.elements(), this.offset + outputDataIndexOffset * this.maxVerticalSliceCount, source.size);
		}
	}
	
	public void copyTo(long[] target, int offset, int size) { System.arraycopy(this.data.elements(), this.offset, target, offset, size); }
	
	public void changeVerticalSizeFrom(ColumnArrayView source)
	{
		if (this.subViewCount() != source.subViewCount())
		{
			throw new IllegalArgumentException("Cannot copy and resize to views with different dataCounts");
		}
		
		if (this.maxVerticalSliceCount >= source.maxVerticalSliceCount)
		{
			this.copyFrom(source);
		}
		else
		{
			for (int i = 0; i < this.subViewCount(); i++)
			{
				mergeMultiData(source.subView(i, 1), this.subView(i, 1));
			}
		}
	}
	/**
	 * This method merge column of multiple data together
	 *
	 * @param sourceData one or more columns of data
	 * @param output one column of space for the result to be written to
	 */
	private static void mergeMultiData(ColumnArrayView sourceData, ColumnArrayView output)
	{
		int target = output.maxVerticalSliceCount;
		if (target <= 0)
		{
			// I expect this to never be the case,
			// but RenderDataPointReducingList handles it sanely,
			// so I might as well handle it sanely here too.
			output.fill(RenderDataPointUtil.EMPTY_DATA);
		}
		else if (target == 1)
		{
			output.set(0, RenderDataPointReducingList.reduceToOne(sourceData));
			for (int index = 1, size = output.size; index < size; index++)
			{
				output.set(index, RenderDataPointUtil.EMPTY_DATA);
			}
		}
		else
		{
			try (RenderDataPointReducingList list = new RenderDataPointReducingList(sourceData))
			{
				list.reduce(output.maxVerticalSliceCount);
				list.copyTo(output);
			}
		}
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("S:").append(this.size);
		sb.append(" V:").append(this.maxVerticalSliceCount);
		sb.append(" O:").append(this.offset);
		
		sb.append(" [");
		for (int i = 0; i < this.size; i++)
		{
			sb.append(RenderDataPointUtil.toString(this.data.getLong(this.offset + i)));
			if (i < this.size - 1)
			{
				sb.append(",\n");
			}
		}
		sb.append("]");
		
		return sb.toString();
	}
	
	//endregion
	
	
	
}