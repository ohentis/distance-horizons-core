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
 
package com.seibel.distanthorizons.core.pos;

import java.util.Objects;

public class DhChunkPos
{
    public final int x; // Low 32 bits
    public final int z; // High 32 bits
	
	/** cached to improve hashing speed */
	public final int hashCode;
	
	
    public DhChunkPos(int x, int z)
	{
        this.x = x;
        this.z = z;
		
		// custom hash, 7309 is a random prime
		this.hashCode = this.x * 7309 + this.z;
    }
    public DhChunkPos(DhBlockPos blockPos)
	{
		// >> 4 is the Same as div 16
		this(blockPos.x >> 4, blockPos.z >> 4);
    }
    public DhChunkPos(DhBlockPos2D blockPos)
	{
		// >> 4 is the Same as div 16
		this(blockPos.x >> 4, blockPos.z >> 4);
    }
	public DhChunkPos(long packed) { this(getX(packed), getZ(packed)); }
	
	
	
	public DhBlockPos center() { return new DhBlockPos(8 + x << 4, 0, 8 + z << 4); }
    public DhBlockPos corner() { return new DhBlockPos(x << 4, 0, z << 4); }

    public static long toLong(int x, int z) { return ((long)x & 0xFFFFFFFFL) << 32 | (long)z & 0xFFFFFFFFL; }

    public static int getX(long chunkPos) { return (int)(chunkPos >> 32); }
    public static int getZ(long chunkPos) { return (int)(chunkPos & 0xFFFFFFFFL); }

    @Deprecated
    public int getX() { return x; }
    @Deprecated
    public int getZ() { return z; }

    public int getMinBlockX() { return x << 4; }
    public int getMinBlockZ() { return z << 4; }

    public DhBlockPos2D getMinBlockPos() { return new DhBlockPos2D(x<<4, z<<4); }

    public long getLong() { return toLong(x, z); }

    @Override
	public boolean equals(Object obj)
	{
		if (this == obj)
		{
			return true;
		}
		else if (obj == null || getClass() != obj.getClass())
		{
			return false;
		}
		else
		{
			DhChunkPos that = (DhChunkPos) obj;
			return x == that.x && z == that.z;	
		}
	}

    @Override
    public int hashCode() { return this.hashCode; }

    @Override
    public String toString() { return "C["+x+","+z+"]"; }
	
	
	
	//=======================//
	// static helper methods //
	//=======================//

    public static void _DebugCheckPacker(int x, int z, long expected)
	{
        long packed = toLong(x, z);
		if (packed != expected)
		{
			throw new IllegalArgumentException("Packed values don't match: "+packed+" != "+expected);
		}
		
		DhChunkPos pos = new DhChunkPos(packed);
		if (pos.x != x || pos.z != z)
		{
			throw new IllegalArgumentException("Values after decode don't match: "+pos+" != "+x+", "+z);
		}
    }
	
	/** @return true if testPos is within the area defined by the min and max positions. */
	public static boolean isChunkPosBetween(DhChunkPos minChunkPos, DhChunkPos testPos, DhChunkPos maxChunkPos)
	{
		int minChunkX = Math.min(minChunkPos.x, maxChunkPos.x);
		int minChunkZ = Math.min(minChunkPos.z, maxChunkPos.z);
		
		int maxChunkX = Math.max(minChunkPos.x, maxChunkPos.x);
		int maxChunkZ = Math.max(minChunkPos.z, maxChunkPos.z);
		
		return minChunkX <= testPos.x && testPos.x <= maxChunkX &&
			   minChunkZ <= testPos.z && testPos.z <= maxChunkZ;
	}
	
	
}
