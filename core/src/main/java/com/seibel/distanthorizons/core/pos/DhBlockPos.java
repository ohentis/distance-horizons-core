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

import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.util.LodUtil;

import org.jetbrains.annotations.Nullable;
import java.util.Objects;

public class DhBlockPos
{
	/** Useful for methods that need a position passed in but won't actually be used */
	public static final DhBlockPos ZERO = new DhBlockPos(0, 0, 0);
	
	
	public int x;
	public int y;
	public int z;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public DhBlockPos(int x, int y, int z)
	{
		this.x = x;
		this.y = y;
		this.z = z;
	}
	public DhBlockPos() { this(0, 0, 0); }
	public DhBlockPos(DhBlockPos pos) { this(pos.x, pos.y, pos.z); }
	
	public DhBlockPos(DhBlockPos2D pos, int y) { this(pos.x, y, pos.z); }
	
	
	
	//========//
	// offset //
	//========//
	
	/** creates a new {@link DhBlockPos} with the given offset from the current pos. */
	public DhBlockPos offset(EDhDirection direction) { return this.mutateOffset(direction, null); }
	/** if not null, mutates "mutablePos" so it matches the current pos after being offset. Otherwise creates a new {@link DhBlockPos}. */
	public DhBlockPos mutateOffset(EDhDirection direction, @Nullable DhBlockPos mutablePos) { return this.mutateOffset(direction.getNormal().x, direction.getNormal().y, direction.getNormal().z, mutablePos); }
	
	public DhBlockPos offset(int x, int y, int z) { return this.mutateOffset(x,y,z, null); }
	public DhBlockPos mutateOffset(int x, int y, int z, @Nullable DhBlockPos mutablePos) 
	{
		int newX = this.x + x;
		int newY = this.y + y;
		int newZ = this.z + z;
		
		if (mutablePos != null)
		{
			mutablePos.x = newX;
			mutablePos.y = newY;
			mutablePos.z = newZ;
			
			return mutablePos;
		}
		else
		{
			return new DhBlockPos(newX, newY, newZ);
		}
	}
	
	
	
	//==========//
	// mutators //
	//==========//
	
	/** Returns a new {@link DhBlockPos} limited to a value between 0 and 15 (inclusive) */
	public DhBlockPos createChunkRelativePos() { return this.mutateOrCreateChunkRelativePos(null); }
	/** Limits the input {@link DhBlockPos} to a value between 0 and 15 (inclusive) */
	public void mutateToChunkRelativePos(DhBlockPos mutableBlockPos) { this.mutateOrCreateChunkRelativePos(mutableBlockPos); }
	/** 
	 * Limits the block position to a value between 0 and 15 (inclusive) 
	 * If not null, mutates "mutableBlockPos" 
	 * 
	 * @return the mutated or created {@link DhBlockPos}
	 */
	private DhBlockPos mutateOrCreateChunkRelativePos(@Nullable DhBlockPos mutableBlockPos)
	{
		// move the position into the range -15 and +15
		int relX = (this.x % LodUtil.CHUNK_WIDTH);
		// if the position is negative move it into the range 0 and 15
		relX = (relX < 0) ? (relX + LodUtil.CHUNK_WIDTH) : relX;
		
		int relZ = (this.z % LodUtil.CHUNK_WIDTH);
		relZ = (relZ < 0) ? (relZ + LodUtil.CHUNK_WIDTH) : relZ;
		
		// the y value shouldn't need to be changed
		
		
		if (mutableBlockPos != null)
		{
			mutableBlockPos.x = relX;
			mutableBlockPos.y = this.y;
			mutableBlockPos.z = relZ;
			
			return mutableBlockPos;
		}
		else
		{
			return new DhBlockPos(relX, this.y, relZ);
		}
	}
	
	
	
	//==========//
	// distance //
	//==========//
	
	/**
	 * Can be used to quickly determine the rough distance between two points<Br>
	 * or determine the taxi cab (manhattan) distance between two points. <Br><Br>
	 *
	 * Manhattan distance is equivalent to determining the distance between two street intersections,
	 * where you can only drive along each street, instead of directly to the other point.
	 */
	public int getManhattanDistance(DhBlockPos otherPos)
	{ return Math.abs(this.x - otherPos.x) + Math.abs(this.y - otherPos.y) + Math.abs(this.z - otherPos.z); }
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
		{
			return true;
		}
		else if (obj == null || this.getClass() != obj.getClass())
		{
			return false;
		}
		else
		{
			DhBlockPos that = (DhBlockPos) obj;
			return this.x == that.x && this.y == that.y && this.z == that.z;
		}
	}
	
	@Override
	public int hashCode() { return Objects.hash(this.x, this.y, this.z); }
	@Override
	public String toString() { return "DHBlockPos["+this.x+", "+this.y+", "+this.z+"]"; }
	
}
