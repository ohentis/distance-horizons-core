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

package com.seibel.distanthorizons.core.render.glObject.vertexAttribute;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.TreeMap;
import java.util.TreeSet;

import com.seibel.distanthorizons.core.render.glObject.GLProxy;
import org.lwjgl.opengl.GL32;


public final class VertexAttributePreGL43 extends VertexAttribute
{
	
	// I tried to use as much raw arrays as possible as those lookups
	// happens every frame, and the speed directly effects fps
	int strideSize = 0;
	int[][] bindingPointsToIndex;
	VertexPointer[] pointers;
	int[] pointersOffset;
	
	TreeMap<Integer, TreeSet<Integer>> bindingPointsToIndexBuilder;
	ArrayList<VertexPointer> pointersBuilder;
	
	// This will bind VertexAttribute
	public VertexAttributePreGL43()
	{
		super(); // also bind VertexAttribute
		bindingPointsToIndexBuilder = new TreeMap<Integer, TreeSet<Integer>>();
		pointersBuilder = new ArrayList<VertexPointer>();
	}
	
	@Override
	// Requires VertexAttribute binded, VertexBuffer binded
	public void bindBufferToAllBindingPoint(int buffer)
	{
		for (int i = 0; i < pointers.length; i++)
		{
			GL32.glEnableVertexAttribArray(i);
		}
		
		for (int i = 0; i < pointers.length; i++)
		{
			VertexPointer pointer = pointers[i];
			if (pointer == null) continue;
			if (pointer.useInteger)
				GL32.glVertexAttribIPointer(i, pointer.elementCount, pointer.glType,
						strideSize, pointersOffset[i]);
			else GL32.glVertexAttribPointer(i, pointer.elementCount, pointer.glType,
					pointer.normalized, strideSize, pointersOffset[i]);
		}
	}
	
	@Override
	// Requires VertexAttribute binded, VertexBuffer binded
	public void bindBufferToBindingPoint(int buffer, int bindingPoint)
	{
		int[] toBind = bindingPointsToIndex[bindingPoint];
		
		for (int k : toBind)
		{
			GL32.glEnableVertexAttribArray(k);
		}
		
		for (int j : toBind)
		{
			VertexPointer pointer = pointers[j];
			if (pointer == null)
				continue;
			if (pointer.useInteger)
				GL32.glVertexAttribIPointer(j, pointer.elementCount, pointer.glType,
						strideSize, pointersOffset[j]);
			else GL32.glVertexAttribPointer(j, pointer.elementCount, pointer.glType,
					pointer.normalized, strideSize, pointersOffset[j]);
		}
		
	}
	@Override
	// Requires VertexAttribute binded
	public void unbindBuffersFromAllBindingPoint()
	{
		for (int i = 0; i < pointers.length; i++)
		{
			GL32.glDisableVertexAttribArray(i);
		}
	}
	
	@Override
	// Requires VertexAttribute binded
	public void unbindBuffersFromBindingPoint(int bindingPoint)
	{
		int[] toBind = bindingPointsToIndex[bindingPoint];
		
		for (int j : toBind)
		{
			GL32.glDisableVertexAttribArray(j);
		}
	}
	
	@Override
	// Requires VertexAttribute binded
	public void setVertexAttribute(int bindingPoint, int attributeIndex, VertexPointer attribute)
	{
		TreeSet<Integer> intArray = bindingPointsToIndexBuilder.computeIfAbsent(bindingPoint, k -> new TreeSet<Integer>());
		intArray.add(attributeIndex);
		
		while (pointersBuilder.size() <= attributeIndex)
		{
			// This is dumb, but ArrayList doesn't have a resize, And this code
			// should only be ran when it's building the Vertex Attribute anyways.
			pointersBuilder.add(null);
		}
		pointersBuilder.set(attributeIndex, attribute);
	}
	
	@Override
	// Requires VertexAttribute binded
	public void completeAndCheck(int expectedStrideSize)
	{
		int maxBindPointNumber = bindingPointsToIndexBuilder.lastKey();
		bindingPointsToIndex = new int[maxBindPointNumber + 1][];
		
		bindingPointsToIndexBuilder.forEach((Integer i, TreeSet<Integer> set) -> {
			bindingPointsToIndex[i] = new int[set.size()];
			Iterator<Integer> iter = set.iterator();
			for (int j = 0; j < set.size(); j++)
			{
				bindingPointsToIndex[i][j] = iter.next();
			}
		});
		
		pointers = pointersBuilder.toArray(new VertexPointer[pointersBuilder.size()]);
		pointersOffset = new int[pointers.length];
		pointersBuilder = null; // Release the builder
		bindingPointsToIndexBuilder = null; // Release the builder
		
		// Check if all pointers are valid
		int currentOffset = 0;
		for (int i = 0; i < pointers.length; i++)
		{
			VertexPointer pointer = pointers[i];
			if (pointer == null)
			{
				GLProxy.GL_LOGGER.warn("Vertex Attribute index " + i + " is not set! No index should be skipped normally!");
				continue;
			}
			pointersOffset[i] = currentOffset;
			currentOffset += pointer.byteSize;
		}
		if (currentOffset != expectedStrideSize)
		{
			GLProxy.GL_LOGGER.error("Vertex Attribute calculated stride size " + currentOffset +
					" does not match the provided expected stride size " + expectedStrideSize + "!");
			throw new IllegalArgumentException("Vertex Attribute Incorrect Format");
		}
		strideSize = currentOffset;
		GLProxy.GL_LOGGER.info("Vertex Attribute (pre GL43) completed.");
		
		// Debug logging
		GLProxy.GL_LOGGER.debug("AttributeIndex: ElementCount, glType, normalized, strideSize, offset");
		
		for (int i = 0; i < pointers.length; i++)
		{
			VertexPointer pointer = pointers[i];
			if (pointer == null)
			{
				GLProxy.GL_LOGGER.debug(i + ": Null!!!!");
				continue;
			}
			GLProxy.GL_LOGGER.debug(i + ": " + pointer.elementCount + ", " +
					pointer.glType + ", " + pointer.normalized + ", " + strideSize + ", " + pointersOffset[i]);
		}
		
	}
	
}
