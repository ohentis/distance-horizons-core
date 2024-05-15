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

package com.seibel.distanthorizons.core.sql.repo;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.OldDhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FullDataSourceV2Repo extends AbstractDhRepo<OldDhSectionPos, FullDataSourceV2DTO>
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public FullDataSourceV2Repo(String databaseType, String databaseLocation) throws SQLException
	{
		super(databaseType, databaseLocation, FullDataSourceV2DTO.class);
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override 
	public String getTableName() { return "FullData"; }
	
	@Override
	public String createWhereStatement(OldDhSectionPos pos) 
	{
		int detailLevel = pos.getDetailLevel() - OldDhSectionPos.SECTION_BLOCK_DETAIL_LEVEL;
		return "DetailLevel = '"+detailLevel+"' AND PosX = '"+pos.getX()+"' AND PosZ = '"+pos.getZ()+"'"; 
	}
	
	
	
	//=======================//
	// repo required methods //
	//=======================//
	
	@Override 
	public FullDataSourceV2DTO convertDictionaryToDto(Map<String, Object> objectMap) throws ClassCastException
	{
		byte detailLevel = (Byte) objectMap.get("DetailLevel");
		byte sectionDetailLevel = (byte) (detailLevel + OldDhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		int posX = (Integer) objectMap.get("PosX");
		int posZ = (Integer) objectMap.get("PosZ");
		OldDhSectionPos pos = new OldDhSectionPos(sectionDetailLevel, posX, posZ);
		
		int minY = (Integer) objectMap.get("MinY");
		int dataChecksum = (Integer) objectMap.get("DataChecksum");
		
		byte[] dataByteArray = (byte[]) objectMap.get("Data");
		byte[] columnGenStepByteArray = (byte[]) objectMap.get("ColumnGenerationStep");
		byte[] columnWorldCompressionByteArray = (byte[]) objectMap.get("ColumnWorldCompressionMode");
		byte[] mappingByteArray = (byte[]) objectMap.get("Mapping");
		
		
		byte dataFormatVersion = (Byte) objectMap.get("DataFormatVersion");
		byte compressionModeValue = (Byte) objectMap.get("CompressionMode");
		
		boolean applyToParent = ((int) objectMap.get("ApplyToParent")) == 1;
		
		long lastModifiedUnixDateTime = (Long) objectMap.get("LastModifiedUnixDateTime");
		long createdUnixDateTime = (Long) objectMap.get("CreatedUnixDateTime");
		
		FullDataSourceV2DTO dto = new FullDataSourceV2DTO(
				pos,
				dataChecksum, columnGenStepByteArray, columnWorldCompressionByteArray, dataFormatVersion, compressionModeValue, dataByteArray,
				lastModifiedUnixDateTime, createdUnixDateTime,
				mappingByteArray, applyToParent,
				minY);
		return dto;
	}
	
	@Override
	public PreparedStatement createInsertStatement(FullDataSourceV2DTO dto) throws SQLException
	{
		String sql =
			"INSERT INTO "+this.getTableName() + " (\n" +
			"   DetailLevel, PosX, PosZ, \n" +
			"   MinY, DataChecksum, \n" +
			"   Data, ColumnGenerationStep, ColumnWorldCompressionMode, Mapping, \n" +
			"   DataFormatVersion, CompressionMode, ApplyToParent, \n" +
			"   LastModifiedUnixDateTime, CreatedUnixDateTime) \n" +
			"VALUES( \n" +
			"    ?, ?, ?, \n" +
			"    ?, ?, \n" +
			"    ?, ?, ?, ?, \n" +
			"    ?, ?, ?, \n" +
			"    ?, ? \n" +
			");";
		PreparedStatement statement = this.createPreparedStatement(sql);
		
		int i = 1;
		statement.setObject(i++, dto.pos.getDetailLevel() - OldDhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		statement.setObject(i++, dto.pos.getX());
		statement.setObject(i++, dto.pos.getZ());
		
		statement.setObject(i++, dto.levelMinY);
		statement.setObject(i++, dto.dataChecksum);
		
		statement.setObject(i++, dto.compressedDataByteArray);
		statement.setObject(i++, dto.compressedColumnGenStepByteArray);
		statement.setObject(i++, dto.compressedWorldCompressionModeByteArray);
		statement.setObject(i++, dto.compressedMappingByteArray);
		
		statement.setObject(i++, dto.dataFormatVersion);
		statement.setObject(i++, dto.compressionModeValue);
		statement.setObject(i++, dto.applyToParent);
		
		statement.setObject(i++, System.currentTimeMillis()); // last modified unix time
		statement.setObject(i++, System.currentTimeMillis()); // created unix time
		
		return statement;
	}
	
	@Override
	public PreparedStatement createUpdateStatement(FullDataSourceV2DTO dto) throws SQLException
	{
		String sql =
			"UPDATE "+this.getTableName()+" \n" +
			"SET \n" +
			"    MinY = ? \n" +
			"   ,DataChecksum = ? \n" +
					
			"   ,Data = ? \n" +
			"   ,ColumnGenerationStep = ? \n" +
			"   ,ColumnWorldCompressionMode = ? \n" +
			"   ,Mapping = ? \n" +
					
			"   ,DataFormatVersion = ? \n" +
			"   ,CompressionMode = ? \n" +
			"   ,ApplyToParent = ? \n" +
					
			"   ,LastModifiedUnixDateTime = ? \n" +
			"   ,CreatedUnixDateTime = ? \n" +
					
			"WHERE DetailLevel = ? AND PosX = ? AND PosZ = ?";
		PreparedStatement statement = this.createPreparedStatement(sql);
		
		int i = 1;
		statement.setObject(i++, dto.levelMinY);
		statement.setObject(i++, dto.dataChecksum);
		
		statement.setObject(i++, dto.compressedDataByteArray);
		statement.setObject(i++, dto.compressedColumnGenStepByteArray);
		statement.setObject(i++, dto.compressedWorldCompressionModeByteArray);
		statement.setObject(i++, dto.compressedMappingByteArray);
		
		statement.setObject(i++, dto.dataFormatVersion);
		statement.setObject(i++, dto.compressionModeValue);
		statement.setObject(i++, dto.applyToParent);
		
		statement.setObject(i++, System.currentTimeMillis()); // last modified unix time
		statement.setObject(i++, dto.createdUnixDateTime);
		
		statement.setObject(i++, dto.pos.getDetailLevel() - OldDhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		statement.setObject(i++, dto.pos.getX());
		statement.setObject(i++, dto.pos.getZ());
		
		return statement;
	}
	
	
	
	// updates //
	
	public void setApplyToParent(OldDhSectionPos pos, boolean applyToParent) throws SQLException
	{
		int detailLevel = pos.getDetailLevel() - OldDhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
		
		String sql =
			"UPDATE "+this.getTableName()+" \n" +
			"SET ApplyToParent = "+applyToParent+" \n" +
			"WHERE DetailLevel = "+detailLevel+" AND PosX = "+pos.getX()+" AND PosZ = "+pos.getZ();
		
		this.queryDictionaryFirst(sql);
	}
	
	public ArrayList<OldDhSectionPos> getPositionsToUpdate(int returnCount)
	{
		ArrayList<OldDhSectionPos> list = new ArrayList<>();
		
		List<Map<String, Object>> resultMapList = this.queryDictionary(
				"select DetailLevel, PosX, PosZ " +
					"from "+this.getTableName()+" " +
					"where ApplyToParent = 1 " +
					"order by DetailLevel asc LIMIT "+returnCount+";");
		
		for (Map<String, Object> resultMap : resultMapList)
		{
			byte detailLevel = (Byte) resultMap.get("DetailLevel");
			byte sectionDetailLevel = (byte) (detailLevel + OldDhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
			int posX = (Integer) resultMap.get("PosX");
			int posZ = (Integer) resultMap.get("PosZ");
			
			OldDhSectionPos pos = new OldDhSectionPos(sectionDetailLevel, posX, posZ);
			list.add(pos);
		}
		
		return list;
	}
	
	/** @return null if nothing exists for this position */
	public byte[] getColumnGenerationStepForPos(OldDhSectionPos pos)
	{
		int detailLevel = pos.getDetailLevel() - OldDhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
		
		Map<String, Object> resultMap = this.queryDictionaryFirst(
				"select ColumnGenerationStep, CompressionMode " +
						"from "+this.getTableName()+" " +
						"WHERE DetailLevel = "+detailLevel+" AND PosX = "+pos.getX()+" AND PosZ = "+pos.getZ());
		
		if (resultMap != null)
		{
			byte[] compressedByteArray = (byte[]) resultMap.get("ColumnGenerationStep");
			
			byte compressionModeEnumValue = (byte) resultMap.get("CompressionMode");
			EDhApiDataCompressionMode compressionModeEnum = EDhApiDataCompressionMode.getFromValue(compressionModeEnumValue);
			
			try
			{
				// decompress the data
				ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(compressedByteArray);
				DhDataInputStream compressedIn = new DhDataInputStream(byteArrayInputStream, compressionModeEnum);
				
				byte[] columnGenStepByteArray = new byte[FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH];
				compressedIn.readFully(columnGenStepByteArray);
				
				return columnGenStepByteArray;
			}
			catch (IOException e)
			{
				LOGGER.warn("Decompression issue when getting column gen steps for pos: "+pos, e);
				return null;
			}
		}
		else
		{
			return null;
		}
	}
	
	
	
	//===================//
	// compression tests //
	//===================//
	
	/** @return every position in this database */
	public ArrayList<OldDhSectionPos> getAllPositions()
	{
		ArrayList<OldDhSectionPos> list = new ArrayList<>();

		List<Map<String, Object>> resultMapList = this.queryDictionary(
				"select DetailLevel, PosX, PosZ " +
						"from "+this.getTableName()+"; ");

		for (Map<String, Object> resultMap : resultMapList)
		{
			byte detailLevel = (Byte) resultMap.get("DetailLevel");
			byte sectionDetailLevel = (byte) (detailLevel + OldDhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
			int posX = (Integer) resultMap.get("PosX");
			int posZ = (Integer) resultMap.get("PosZ");

			OldDhSectionPos pos = new OldDhSectionPos(sectionDetailLevel, posX, posZ);
			list.add(pos);
		}

		return list;
	}
	
	/** 
	 * @return the size of the full data at the given position 
	 *          (doesn't include the size of the mapping or any other column)
	 */
	public long getDataSizeInBytes(OldDhSectionPos pos)
	{
		int detailLevel = pos.getDetailLevel() - OldDhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;

		Map<String, Object> resultMap = this.queryDictionaryFirst(
				"select LENGTH(Data) as dataSize " +
						"from "+this.getTableName()+" " +
						"WHERE DetailLevel = "+detailLevel+" AND PosX = "+pos.getX()+" AND PosZ = "+pos.getZ());
		
		if (resultMap != null && resultMap.get("dataSize") != null)
		{
			// Number cast is necessary because the returned number can be an int or long
			Number resultNumber = (Number) resultMap.get("dataSize");
			long dataLength = resultNumber.longValue();
			return dataLength;
			
		}
		else
		{
			return 0;
		}
	}
	
	/** @return the total size in bytes of the full data for this entire database */
	public long getTotalDataSizeInBytes()
	{
		Map<String, Object> resultMap = this.queryDictionaryFirst(
				"select SUM(LENGTH(Data)) as dataSize " +
						"from "+this.getTableName()+"; ");
		
		if (resultMap != null && resultMap.get("dataSize") != null)
		{
			Number resultNumber = (Number) resultMap.get("dataSize");
			long dataLength = resultNumber.longValue();
			return dataLength;
			
		}
		else
		{
			return 0;
		}
	}
	
	
}
