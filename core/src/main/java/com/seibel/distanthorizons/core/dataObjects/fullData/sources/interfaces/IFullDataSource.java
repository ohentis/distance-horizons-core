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

package com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.IFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.SingleColumnFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataMetaFile;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;

/**
 * Base for all Full Data Source objects. <br><br>
 *
 * Contains full DH data, methods related to file/stream reading/writing, and the data necessary to create {@link ColumnRenderSource}'s. <br>
 * {@link IFullDataSource}'s will either implement or contain {@link IFullDataAccessor}'s.
 *
 * @see IFullDataAccessor
 * @see IIncompleteFullDataSource
 * @see IStreamableFullDataSource
 */
public interface IFullDataSource
{
	/**
	 * This is the byte put between different sections in the binary save file.
	 * The presence and absence of this byte indicates if the file is correctly formatted.
	 */
	int DATA_GUARD_BYTE = 0xFFFFFFFF;
	/** indicates the binary save file represents an empty data source */
	int NO_DATA_FLAG_BYTE = 0x00000001;
	
	
	
	DhSectionPos getSectionPos();
	
	/** Returns the detail level of the data contained by this {@link IFullDataSource}. */
	byte getDataDetailLevel();
	long getTypeId();
	byte getBinaryDataFormatVersion();
	EDhApiWorldGenerationStep getWorldGenStep();
	
	void update(ChunkSizedFullDataAccessor data);
	
	boolean isEmpty();
	
	/** AKA; the max relative position that {@link IFullDataSource#tryGet(int, int)} can accept for either X or Z */
	int getWidthInDataPoints();
	
	
	
	//======//
	// data //
	//======//
	
	/**
	 * Attempts to get the data column for the given relative x and z position.
	 *
	 * @return null if the data doesn't exist
	 */
	SingleColumnFullDataAccessor tryGet(int relativeX, int relativeZ);
	
	FullDataPointIdMap getMapping();
	
	/**
	 * @param highestGeneratorDetailLevel the smallest numerical detail level that the un-generated positions should be split into
	 * @return the list of {@link DhSectionPos} that aren't generated in this data source.
	 */
	default ArrayList<DhSectionPos> getUngeneratedPosList(byte highestGeneratorDetailLevel, boolean onlyReturnPositionsTheGeneratorCanAccept)
	{
		ArrayList<DhSectionPos> posArray = this.getUngeneratedPosList(this.getSectionPos(), highestGeneratorDetailLevel);
		
		if (onlyReturnPositionsTheGeneratorCanAccept)
		{
			LinkedList<DhSectionPos> posList = new LinkedList<>(posArray);
			
			ArrayList<DhSectionPos> cleanedPosArray = new ArrayList<>();
			while (posList.size() > 0)
			{
				DhSectionPos pos = posList.remove();
				if (pos.getDetailLevel() > highestGeneratorDetailLevel)
				{
					pos.forEachChild((childPos) -> { posList.push(childPos); });
				}
				else
				{
					cleanedPosArray.add(pos);
				}
			}
			
			return cleanedPosArray;
		}
		else
		{
			return posArray;
		}
	}
	default ArrayList<DhSectionPos> getUngeneratedPosList(DhSectionPos quadrantPos, byte highestGeneratorDetailLevel)
	{
		ArrayList<DhSectionPos> ungeneratedPosList = new ArrayList<>();
		
		int sourceRelWidth = this.getWidthInDataPoints();
		
		
		if (quadrantPos.getDetailLevel() < highestGeneratorDetailLevel)
		{
			throw new IllegalArgumentException("detail level lower than world generator can accept.");
		}
		else if (quadrantPos.getDetailLevel() == highestGeneratorDetailLevel)
		{
			// we are at the highest detail level the world generator can accept,
			// we either need to generate this whole section, or not at all
			
			// TODO combine duplicate code
			
			byte childDetailLevel = (byte) (quadrantPos.getDetailLevel());
			
			int quadrantDetailLevelDiff = this.getSectionPos().getDetailLevel() - childDetailLevel;
			int widthInSecPos = BitShiftUtil.powerOfTwo(quadrantDetailLevelDiff);
			int relWidthForSecPos = sourceRelWidth / widthInSecPos;
			
			DhSectionPos minSecPos = this.getSectionPos().convertNewToDetailLevel(childDetailLevel);
			DhSectionPos inputPos = quadrantPos;
			
			
			
			int minRelX = inputPos.getX() - minSecPos.getX();
			int minRelZ = inputPos.getZ() - minSecPos.getZ();
			int maxRelX = minRelX + 1;
			int maxRelZ = minRelZ + 1;
			
			minRelX = minRelX * relWidthForSecPos;
			minRelZ = minRelZ * relWidthForSecPos;
			maxRelX = maxRelX * relWidthForSecPos;
			maxRelZ = maxRelZ * relWidthForSecPos;
			
			
			boolean quadrantFullyGenerated = true;
			for (int relX = minRelX; relX < maxRelX; relX++)
			{
				for (int relZ = minRelZ; relZ < maxRelZ; relZ++)
				{
					SingleColumnFullDataAccessor column = this.tryGet(relX, relZ);
					if (column == null || !column.doesColumnExist())
					{
						// no data for this relative position
						quadrantFullyGenerated = false;
						break;
					}
				}
			}
			
			if (!quadrantFullyGenerated)
			{
				// at least 1 data point is missing,
				// this whole section must be regenerated
				ungeneratedPosList.add(quadrantPos);
			}
		}
		else
		{
			// TODO comment
			// TODO combine duplicate code
			
			byte childDetailLevel = (byte) (quadrantPos.getDetailLevel() - 1);
			
			for (int i = 0; i < 4; i++)
			{
				int quadrantDetailLevelDiff = this.getSectionPos().getDetailLevel() - childDetailLevel;
				int widthInSecPos = BitShiftUtil.powerOfTwo(quadrantDetailLevelDiff);
				int relWidthForSecPos = sourceRelWidth / widthInSecPos;
				
				DhSectionPos minSecPos = this.getSectionPos().convertNewToDetailLevel(childDetailLevel);
				DhSectionPos inputPos = quadrantPos.getChildByIndex(i);
				
				
				
				int minRelX = inputPos.getX() - minSecPos.getX();
				int minRelZ = inputPos.getZ() - minSecPos.getZ();
				int maxRelX = minRelX + 1;
				int maxRelZ = minRelZ + 1;
				
				minRelX = minRelX * relWidthForSecPos;
				minRelZ = minRelZ * relWidthForSecPos;
				maxRelX = maxRelX * relWidthForSecPos;
				maxRelZ = maxRelZ * relWidthForSecPos;
				
				
				
				boolean quadrantFullyGenerated = true;
				boolean quadrantEmpty = true;
				for (int relX = minRelX; relX < maxRelX; relX++)
				{
					for (int relZ = minRelZ; relZ < maxRelZ; relZ++)
					{
						SingleColumnFullDataAccessor column = this.tryGet(relX, relZ);
						if (column == null || !column.doesColumnExist())
						{
							// no data for this relative position
							quadrantFullyGenerated = false;
						}
						else
						{
							// data exists for this pos
							quadrantEmpty = false;
						}
					}
				}
				
				
				if (quadrantFullyGenerated)
				{
					// no generation necessary
					continue;
				}
				else if (quadrantEmpty)
				{
					// nothing exists for this sub quadrant, add this sub-quadrant's position
					ungeneratedPosList.add(inputPos);
				}
				else
				{
					// some data exists in this quadrant, but not all that we need
					// recurse down to determine which sub-quadrant positions will need generation
					
					ungeneratedPosList.addAll(this.getUngeneratedPosList(inputPos, highestGeneratorDetailLevel));
				}
				
			}
		}
		
		return ungeneratedPosList;
	}
	
	
	
	//=======================//
	// basic stream handling // 
	//=======================//
	
	// TODO make this blow up in IStreamableFullDataSource instead of the children
	/**
	 * Should only be implemented by {@link IStreamableFullDataSource} to prevent potential stream read/write inconsistencies.
	 *
	 * @see IStreamableFullDataSource#writeToStream(DhDataOutputStream, IDhLevel)
	 */
	void writeToStream(DhDataOutputStream outputStream, IDhLevel level) throws IOException;
	
	/**
	 * Should only be implemented by {@link IStreamableFullDataSource} to prevent potential stream read/write inconsistencies.
	 *
	 * @see IStreamableFullDataSource#populateFromStream(FullDataMetaFile, DhDataInputStream, IDhLevel)
	 */
	void populateFromStream(FullDataMetaFile dataFile, DhDataInputStream inputStream, IDhLevel level) throws IOException, InterruptedException;
	
}
