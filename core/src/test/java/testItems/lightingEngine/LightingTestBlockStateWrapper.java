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

package testItems.lightingEngine;

import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import tests.LightingEngineTest;

/**
 * @see LightingEngineTest
 * @see LightingTestChunkWrapper
 */
public class LightingTestBlockStateWrapper implements IBlockStateWrapper
{
	private int opacity = -1;
	private int lightEmission = -1;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public LightingTestBlockStateWrapper(int opacity, int lightEmission)
	{
		this.opacity = opacity;
		this.lightEmission = lightEmission;
	}
	
	
	
	//=================//
	// wrapper methods //
	//=================//
	
	@Override
	public int getOpacity() { return this.opacity; }
	
	@Override
	public int getLightEmission() { return this.lightEmission; }
	
	
	
	
	//===============//
	// unimplemented //
	//===============//
	
	//@Override
	//public boolean equals(Object obj)
	//{
	//	if (this == obj)
	//	{
	//		return true;
	//	}
	//	
	//	if (obj == null || this.getClass() != obj.getClass())
	//	{
	//		return false;
	//	}
	//	
	//	BlockStateTestWrapper that = (BlockStateTestWrapper) obj;
	//	// the serialized value is used so we can test the contents instead of the references
	//	return Objects.equals(this.getSerialString(), that.getSerialString());
	//}
	
	//@Override
	//public int hashCode() { return this.hashCode; }
	//@Override
	//public String toString() { return this.getSerialString(); }
	
	
	@Override
	public String getSerialString() { throw new UnsupportedOperationException("Not Implemented"); }
	
	@Override
	public Object getWrappedMcObject() { throw new UnsupportedOperationException("Not Implemented"); }
	
	@Override
	public boolean isAir() { throw new UnsupportedOperationException("Not Implemented"); }
	
	@Override
	public boolean isSolid() { throw new UnsupportedOperationException("Not Implemented"); }
	
	@Override
	public boolean isLiquid() { throw new UnsupportedOperationException("Not Implemented"); }
	
	@Override
	public byte getIrisBlockMaterialId() { throw new UnsupportedOperationException("Not Implemented"); }
	
}
