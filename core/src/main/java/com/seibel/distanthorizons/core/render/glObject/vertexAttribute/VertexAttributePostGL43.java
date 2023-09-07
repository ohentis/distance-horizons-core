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

import com.seibel.distanthorizons.core.render.glObject.GLProxy;
import org.lwjgl.opengl.GL43;

// In OpenGL 4.3 and later, Vertex Attribute got a make-over.
// Now it provides support for buffer binding points natively.
// This means that setting up the VAO just use ONE native call when
// binding to a buffer.
//
// Since I no longer needs to implement binding points, I also no
// longer needs to keep track of Pointers.

public final class VertexAttributePostGL43 extends VertexAttribute
{
	
	int numberOfBindingPoints = 0;
	int strideSize = 0;
	
	// This will bind VertexAttribute
	public VertexAttributePostGL43()
	{
		super(); // also bind VertexAttribute
	}
	
	@Override
	// Requires VertexAttribute binded, VertexBuffer binded
	public void bindBufferToAllBindingPoint(int buffer)
	{
		for (int i = 0; i < numberOfBindingPoints; i++)
		{
			GL43.glBindVertexBuffer(i, buffer, 0, strideSize);
		}
	}
	
	@Override
	// Requires VertexAttribute binded, VertexBuffer binded
	public void bindBufferToBindingPoint(int buffer, int bindingPoint)
	{
		GL43.glBindVertexBuffer(bindingPoint, buffer, 0, strideSize);
	}
	
	@Override
	// Requires VertexAttribute binded
	public void unbindBuffersFromAllBindingPoint()
	{
		for (int i = 0; i < numberOfBindingPoints; i++)
		{
			GL43.glBindVertexBuffer(i, 0, 0, 0);
		}
	}
	
	@Override
	// Requires VertexAttribute binded
	public void unbindBuffersFromBindingPoint(int bindingPoint)
	{
		GL43.glBindVertexBuffer(bindingPoint, 0, 0, 0);
	}
	
	@Override
	// Requires VertexAttribute binded
	public void setVertexAttribute(int bindingPoint, int attributeIndex, VertexPointer attribute)
	{
		if (attribute.useInteger)
			GL43.glVertexAttribIFormat(attributeIndex, attribute.elementCount, attribute.glType, strideSize);
		else
			GL43.glVertexAttribFormat(attributeIndex, attribute.elementCount, attribute.glType,
					attribute.normalized, strideSize); // Here strideSize is new attrib offset
		strideSize += attribute.byteSize;
		if (numberOfBindingPoints <= bindingPoint) numberOfBindingPoints = bindingPoint + 1;
		GL43.glVertexAttribBinding(attributeIndex, bindingPoint);
		GL43.glEnableVertexAttribArray(attributeIndex);
	}
	
	@Override
	// Requires VertexAttribute binded
	public void completeAndCheck(int expectedStrideSize)
	{
		if (strideSize != expectedStrideSize)
		{
			GLProxy.GL_LOGGER.error("Vertex Attribute calculated stride size " + strideSize +
					" does not match the provided expected stride size " + expectedStrideSize + "!");
			throw new IllegalArgumentException("Vertex Attribute Incorrect Format");
		}
		GLProxy.GL_LOGGER.info("Vertex Attribute (GL43+) completed. It contains " + numberOfBindingPoints
				+ " binding points and a stride size of " + strideSize);
	}
	
}
