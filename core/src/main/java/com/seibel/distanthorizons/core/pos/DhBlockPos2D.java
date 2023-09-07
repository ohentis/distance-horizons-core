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

package com.seibel.distanthorizons.core.pos;

import com.seibel.distanthorizons.coreapi.util.MathUtil;

public class DhBlockPos2D
{
	public static final DhBlockPos2D ZERO = new DhBlockPos2D(0, 0);
	public final int x;
	public final int z;
	public DhBlockPos2D(int x, int z)
	{
		this.x = x;
		this.z = z;
	}
	
	public DhBlockPos2D(DhBlockPos blockPos)
	{
		this.x = blockPos.x;
		this.z = blockPos.z;
	}
	
	public DhBlockPos2D add(DhBlockPos2D other)
	{
		return new DhBlockPos2D(x + other.x, z + other.z);
	}
	
	public DhBlockPos2D add(int offsetX, int offsetZ)
	{
		return new DhBlockPos2D(x + offsetX, z + offsetZ);
	}
	
	public DhBlockPos2D subtract(DhBlockPos2D other)
	{
		return new DhBlockPos2D(x - other.x, z - other.z);
	}
	public double dist(DhBlockPos2D other)
	{
		return Math.sqrt(Math.pow(x - other.x, 2) + Math.pow(z - other.z, 2));
	}
	public long distSquared(DhBlockPos2D other)
	{
		return MathUtil.pow2((long) x - other.x) + MathUtil.pow2((long) z - other.z);
	}
	
	public Pos2D toPos2D()
	{
		return new Pos2D(x, z);
	}
	
	public static DhBlockPos2D fromPos2D(Pos2D pos)
	{
		return new DhBlockPos2D(pos.x, pos.y);
	}
	
	@Override
	public String toString()
	{
		return "(" + x + ", " + z + ")";
	}
	
	@Override
	public boolean equals(Object obj)
	{
		if (obj instanceof DhBlockPos2D)
		{
			DhBlockPos2D other = (DhBlockPos2D) obj;
			return x == other.x && z == other.z;
		}
		return false;
	}
	
	@Override
	public int hashCode()
	{
		return Integer.hashCode(x) ^ Integer.hashCode(z);
	}
	
}
