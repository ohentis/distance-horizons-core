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

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.ChunkLightStorage;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.apache.logging.log4j.Logger;
import tests.LightingEngineTest;

import java.io.*;
import java.util.ArrayList;

/** 
 * @see LightingEngineTest
 * @see LightingTestBlockStateWrapper
 */
public class LightingTestChunkWrapper implements IChunkWrapper
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	
	// chunk values //
	
	private final DhChunkPos chunkPos;
	private ChunkLightStorage blockLightStorage;
	private ChunkLightStorage skyLightStorage;
	
	private ArrayList<DhBlockPos> blockLightPosList = null;
	
	private boolean useDhLighting;
	
	private int minNonEmptyHeight = Integer.MIN_VALUE;
	private int maxNonEmptyHeight = Integer.MAX_VALUE;
	
	
	// test values //
	
	private final Int2IntOpenHashMap blockOpacityStorage;
	private final Int2IntOpenHashMap blockEmissionStorage;
	private final int[][] solidHeightMap = new int[LodUtil.CHUNK_WIDTH][LodUtil.CHUNK_WIDTH];
	private final int[][] lightBlockingHeightMap = new int[LodUtil.CHUNK_WIDTH][LodUtil.CHUNK_WIDTH];
	
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public LightingTestChunkWrapper(IChunkWrapper chunkWrapper)
	{
		this.chunkPos = chunkWrapper.getChunkPos();
		
		this.blockOpacityStorage = new Int2IntOpenHashMap();
		this.blockEmissionStorage = new Int2IntOpenHashMap();
		this.blockLightPosList = new ArrayList<>();
		
		for (int x = 0; x < LodUtil.CHUNK_WIDTH; x++)
		{
			for (int z = 0; z < LodUtil.CHUNK_WIDTH; z++)
			{
				for (int y = this.getMinBuildHeight(); y < this.getMaxBuildHeight(); y++)
				{
					IBlockStateWrapper block = chunkWrapper.getBlockState(x,y,z);
					
					int opacity = block.getOpacity();
					if (opacity >= LodUtil.BLOCK_FULLY_OPAQUE)
					{
						opacity = 3;
					}
					
					this.blockOpacityStorage.put(new DhBlockPos(x, y, z).hashCode(), opacity);
					this.blockEmissionStorage.put(new DhBlockPos(x, y, z).hashCode(), block.getLightEmission());
					
					if (block.getLightEmission() != 0)
					{
						this.blockLightPosList.add(new DhBlockPos(x,y,z));
					}
				}
				
				this.lightBlockingHeightMap[x][z] = chunkWrapper.getLightBlockingHeightMapValue(x, z);
				this.solidHeightMap[x][z] = chunkWrapper.getSolidHeightMapValue(x, z);
				
			}
		}
	}
	
	
	
	//===============//
	// file handling //
	//===============//
	
	public LightingTestChunkWrapper(DhChunkPos pos, File saveFile) throws DataCorruptedException
	{
		this.chunkPos = pos;
		
		this.blockOpacityStorage = new Int2IntOpenHashMap();
		this.blockEmissionStorage = new Int2IntOpenHashMap();
		this.blockLightPosList = new ArrayList<>();
		
		try(FileInputStream inputStream = new FileInputStream(saveFile);
			BufferedInputStream bufferedStream = new BufferedInputStream(inputStream);
			DataInputStream stream = new DataInputStream(bufferedStream))
		{
			for (int x = 0; x < LodUtil.CHUNK_WIDTH; x++)
			{
				for (int z = 0; z < LodUtil.CHUNK_WIDTH; z++)
				{
					for (int y = this.getMinBuildHeight(); y < this.getMaxBuildHeight(); y++)
					{
						this.blockOpacityStorage.put(new DhBlockPos(x, y, z).hashCode(), stream.readInt());
						
						int blockEmission = stream.readInt();
						this.blockEmissionStorage.put(new DhBlockPos(x, y, z).hashCode(), blockEmission);
						if (blockEmission != 0)
						{
							this.blockLightPosList.add(new DhBlockPos(x,y,z));
						}
					}
					
					if (stream.readChar() != ';')
					{
						throw new DataCorruptedException("bad height map");
					}
					
					this.lightBlockingHeightMap[x][z] = stream.readInt();
					this.solidHeightMap[x][z] = stream.readInt();
					
					if (stream.readChar() != '\n')
					{
						throw new DataCorruptedException(" bad col ending");
					}
				}
			}
		}
		catch (IOException e)
		{
			LOGGER.error("Unable to write to file: ["+e.getMessage()+"].", e);
		}
	}
	public void writeToFile(File file)
	{
		try(FileOutputStream fileStream = new FileOutputStream(file);
			BufferedOutputStream bufferedStream = new BufferedOutputStream(fileStream);
			DataOutputStream stream = new DataOutputStream(bufferedStream))
		{
			for (int x = 0; x < LodUtil.CHUNK_WIDTH; x++)
			{
				for (int z = 0; z < LodUtil.CHUNK_WIDTH; z++)
				{
					for (int y = this.getMinBuildHeight(); y < this.getMaxBuildHeight(); y++)
					{
						stream.writeInt(this.blockOpacityStorage.get(new DhBlockPos(x, y, z).hashCode()));
						stream.writeInt(this.blockEmissionStorage.get(new DhBlockPos(x, y, z).hashCode()));
					}
					
					stream.writeChar(';');
					
					stream.writeInt(this.lightBlockingHeightMap[x][z]);
					stream.writeInt(this.solidHeightMap[x][z]);
					
					stream.writeChar('\n');
				}
			}
			
			stream.flush();
		}
		catch (IOException e)
		{
			LOGGER.error("Unable to write to file: ["+e.getMessage()+"].", e);
		}
	}
	
	/** 
	 * Can be added into {@link com.seibel.distanthorizons.core.api.internal.SharedApi#applyChunkUpdate(IChunkWrapper, ILevelWrapper, boolean)} 
	 * to save chunks to file for future testing.
	 */
	public void tryConvertingAndSavingChunkWrapper(IChunkWrapper chunkWrapper)
	{
		try
		{
			File chunkFile = new File(LightingEngineTest.TEST_DATA_PATH + "/" + chunkWrapper.getChunkPos().toString());
			if (!chunkFile.exists())
			{
				LightingTestChunkWrapper testWrapper = new LightingTestChunkWrapper(chunkWrapper);
				testWrapper.writeToFile(chunkFile);
			}
		}
		catch (Exception e)
		{
			LOGGER.error(e.getMessage(), e);
		}
	}
	
	
	
	//===============//
	// chunk methods //
	//===============//
	
	@Override
	public int getHeight() { return 255; }
	
	@Override
	public int getMinBuildHeight() { return -64; }
	@Override
	public int getMaxBuildHeight() { return 255; }
	
	@Override
	public int getMinNonEmptyHeight()
	{
		if (this.minNonEmptyHeight != Integer.MIN_VALUE)
		{
			return this.minNonEmptyHeight;
		}
		
		
		// default if every section is empty or missing
		this.minNonEmptyHeight = this.getMinBuildHeight();
		
		// determine the lowest empty section (bottom up)
		int maxYHeight = this.getMaxBuildHeight();
		for (int y = this.getMinBuildHeight(); y < maxYHeight; y++)
		{
			if (this.blockOpacityStorage.get(new DhBlockPos(0, y, 0).hashCode()) != 0)
			{
				// -16 to simulate having to populate the full chunk section
				this.minNonEmptyHeight = Math.min(y - 16, maxYHeight);
				break;
			}
		}
		
		return this.minNonEmptyHeight;
	}
	
	
	@Override
	public int getMaxNonEmptyHeight()
	{
		if (this.maxNonEmptyHeight != Integer.MAX_VALUE)
		{
			return this.maxNonEmptyHeight;
		}
		
		
		// default if every section is empty or missing
		this.maxNonEmptyHeight = this.getMaxBuildHeight();
		
		// determine the highest empty section (top down)
		int minYHeight = this.getMinBuildHeight();
		for (int y = this.getMaxBuildHeight(); y >= minYHeight; y--)
		{
			if (this.blockOpacityStorage.get(new DhBlockPos(0, y, 0).hashCode()) != 0)
			{
				// -16 to simulate having to populate the full chunk section
				this.maxNonEmptyHeight = Math.max(y - 16, minYHeight);
				break;
			}
		}
		
		return this.maxNonEmptyHeight;
	}
	
	
	@Override
	public int getSolidHeightMapValue(int xRel, int zRel) { return this.solidHeightMap[xRel][zRel]; }
	
	@Override
	public int getLightBlockingHeightMapValue(int xRel, int zRel) { return this.lightBlockingHeightMap[xRel][zRel]; }
	
	
	
	@Override
	public IBiomeWrapper getBiome(int relX, int relY, int relZ) { throw new UnsupportedOperationException("Not implemented"); }
	
	@Override
	public DhChunkPos getChunkPos() { return this.chunkPos; }
	
	@Override
	public int getMaxBlockX() { return 0; }
	@Override
	public int getMaxBlockZ() { return 0; }
	@Override
	public int getMinBlockX() { return LodUtil.CHUNK_WIDTH; }
	@Override
	public int getMinBlockZ() { return LodUtil.CHUNK_WIDTH; }
	
	@Override
	public void setIsDhLightCorrect(boolean isDhLightCorrect) {  }
	
	@Override
	public void setUseDhLighting(boolean useDhLighting) { this.useDhLighting = useDhLighting; }
	
	
	
	@Override
	public boolean isLightCorrect() { return false; }
	
	
	@Override
	public int getDhBlockLight(int relX, int y, int relZ)
	{
		this.throwIndexOutOfBoundsIfRelativePosOutsideChunkBounds(relX, y, relZ);
		return this.getBlockLightStorage().get(relX, y, relZ);
	}
	@Override
	public void setDhBlockLight(int relX, int y, int relZ, int lightValue)
	{
		this.throwIndexOutOfBoundsIfRelativePosOutsideChunkBounds(relX, y, relZ);
		this.getBlockLightStorage().set(relX, y, relZ, lightValue);
	}
	
	private ChunkLightStorage getBlockLightStorage()
	{
		if (this.blockLightStorage == null)
		{
			this.blockLightStorage = new ChunkLightStorage(
					// +/- 16 is to fix an issue with the test chunk where the storage isn't big enough,
					// James probably just screwed up the min/max height slightly 
					this.getMinBuildHeight() - 16, this.getMaxBuildHeight() + 16, 
					// positions above and below the handled area should be unlit
					LodUtil.MIN_MC_LIGHT, LodUtil.MIN_MC_LIGHT);
		}
		return this.blockLightStorage;
	}
	@Override
	public void clearDhBlockLighting() { throw new UnsupportedOperationException("Not implemented"); }
	
	
	@Override
	public int getDhSkyLight(int relX, int y, int relZ)
	{
		this.throwIndexOutOfBoundsIfRelativePosOutsideChunkBounds(relX, y, relZ);
		return this.getSkyLightStorage().get(relX, y, relZ);
	}
	@Override
	public void setDhSkyLight(int relX, int y, int relZ, int lightValue)
	{
		this.throwIndexOutOfBoundsIfRelativePosOutsideChunkBounds(relX, y, relZ);
		this.getSkyLightStorage().set(relX, y, relZ, lightValue);
	}
	@Override
	public void clearDhSkyLighting() { throw new UnsupportedOperationException("Not implemented"); }
	
	private ChunkLightStorage getSkyLightStorage()
	{
		if (this.skyLightStorage == null)
		{
			this.skyLightStorage = new ChunkLightStorage(
					// +/- 16 is to fix an issue with the test chunk where the storage isn't big enough,
					// James probably just screwed up the min/max height slightly
					this.getMinBuildHeight() - 16, this.getMaxBuildHeight() + 16,
					// positions above should be lit but positions below should be unlit
					LodUtil.MAX_MC_LIGHT, LodUtil.MIN_MC_LIGHT);
		}
		return this.skyLightStorage;
	}
	
	
	@Override
	public int getBlockLight(int relX, int y, int relZ)
	{
		this.throwIndexOutOfBoundsIfRelativePosOutsideChunkBounds(relX, y, relZ);
		return this.getBlockLightStorage().get(relX, y, relZ);
	}
	
	@Override
	public int getSkyLight(int relX, int y, int relZ)
	{
		this.throwIndexOutOfBoundsIfRelativePosOutsideChunkBounds(relX, y, relZ);
		return this.getSkyLightStorage().get(relX, y, relZ);
	}
	
	@Override
	public ArrayList<DhBlockPos> getBlockLightPosList() { return this.blockLightPosList; }
	
	@Override
	public boolean doNearbyChunksExist() { return false; }
	
	@Override
	public String toString() { return this.chunkPos.toString(); }
	
	@Override
	public IBlockStateWrapper getBlockState(int relX, int relY, int relZ)
	{
		this.throwIndexOutOfBoundsIfRelativePosOutsideChunkBounds(relX, relY, relZ);

		int opacity = this.blockOpacityStorage.get(new DhBlockPos(relX, relY, relZ).hashCode());
		int lightEmission = this.blockEmissionStorage.get(new DhBlockPos(relX, relY, relZ).hashCode());
		return new LightingTestBlockStateWrapper(opacity, lightEmission);
	}
	
	@Override
	public boolean isStillValid() { return true; }
	
	
	
}
