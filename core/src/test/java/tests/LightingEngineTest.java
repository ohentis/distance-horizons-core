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

package tests;

import com.seibel.distanthorizons.core.generation.DhLightingEngine;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import testItems.lightingEngine.LightingTestChunkWrapper;

import java.io.File;
import java.util.ArrayList;

/**
 * Can be used to A/B Test lighting engine performance changes. <br><br>
 *
 * normal                   - chunks: [1595], total Time: [1490], avg time: [0.9341692789968652] <br>
 * only surface light prop  - chunks: [1595], total Time: [984], avg time: [0.6169278996865204] <br>
 * 
 * @author James Seibel
 * @version 2024-6-11
 */
public class LightingEngineTest
{
	/**
	 * There should be test data in the following core repo folder: <br>
	 * <code> Core\_Misc Files\test files\Lighting engine test chunk data.7z </code>
	 */
	public static final String TEST_DATA_PATH = "C:/Users/James_Seibel/Desktop/test chunk data";
	
	
	//@Test
	public void TestLightingEngine() throws DataCorruptedException
	{
		long totalNanoTime = 0;
		int chunkCount = 0;
		
		File testFolder = new File(TEST_DATA_PATH);
		File[] chunkFiles = testFolder.listFiles();
		for (int i = 0; i < chunkFiles.length; i++)
		{
			// chunk file parsing //
			
			File chunkFile = chunkFiles[i];
			
			String fileName = chunkFile.getName(); // C[0,-3]
			fileName = fileName.replace("C", "").replace("[", "").replace("]", "");
			int xPos = Integer.parseInt(fileName.split(",")[0]);
			int zPos = Integer.parseInt(fileName.split(",")[1]);
			DhChunkPos pos = new DhChunkPos(xPos, zPos);
			
			if (i % 100 == 0)
			{
				System.out.println(i + "/" + chunkFiles.length);
			}
			
			LightingTestChunkWrapper chunk = new LightingTestChunkWrapper(pos, chunkFile);
			chunkCount++;
			
			ArrayList<IChunkWrapper> nearbyChunkList = new ArrayList<>(1);
			nearbyChunkList.add(chunk);
			
			
			
			// lighting //
			
			long startTime = System.nanoTime();
			DhLightingEngine.INSTANCE.lightChunk(chunk, nearbyChunkList, LodUtil.MAX_MC_LIGHT);
			long endTime = System.nanoTime();
			totalNanoTime += endTime - startTime;
		}
		long timeMs = totalNanoTime / 1_000_000;
		
		
		System.out.println("chunks: ["+chunkCount+"], total Time: ["+timeMs+"], avg time: ["+(timeMs/(double)chunkCount)+"]");
	}
	
}
