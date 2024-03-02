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

import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.NewFullDataSourceDTO;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class NewFullDataSourceRepo extends AbstractDhRepo<DhSectionPos, NewFullDataSourceDTO>
{
	public NewFullDataSourceRepo(String databaseType, String databaseLocation) throws SQLException
	{
		super(databaseType, databaseLocation, NewFullDataSourceDTO.class);
	}
	
	
	
	@Override 
	public String getTableName() { return "FullData"; }
	
	@Override
	public String createWhereStatement(DhSectionPos pos) 
	{
		int detailLevel = pos.getDetailLevel() - DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL;
		return "DetailLevel = '"+detailLevel+"' AND PosX = '"+pos.getX()+"' AND PosZ = '"+pos.getZ()+"'"; 
	}
	
	
	
	//=======================//
	// repo required methods //
	//=======================//
	
	@Override 
	public NewFullDataSourceDTO convertDictionaryToDto(Map<String, Object> objectMap) throws ClassCastException
	{
		byte detailLevel = (Byte) objectMap.get("DetailLevel");
		byte sectionDetailLevel = (byte) (detailLevel + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		int posX = (Integer) objectMap.get("PosX");
		int posZ = (Integer) objectMap.get("PosZ");
		DhSectionPos pos = new DhSectionPos(sectionDetailLevel, posX, posZ);
		
		int minY = (Integer) objectMap.get("MinY");
		int dataChecksum = (Integer) objectMap.get("DataChecksum");
		
		byte[] dataByteArray = (byte[]) objectMap.get("Data");
		byte[] columnGenStepByteArray = (byte[]) objectMap.get("ColumnGenerationStep");
		byte[] mappingByteArray = (byte[]) objectMap.get("Mapping");
		
		
		byte dataFormatVersion = (Byte) objectMap.get("DataFormatVersion");
		
		boolean applyToParent = ((int) objectMap.get("ApplyToParent")) == 1;
		
		long lastModifiedUnixDateTime = (Long) objectMap.get("LastModifiedUnixDateTime");
		long createdUnixDateTime = (Long) objectMap.get("CreatedUnixDateTime");
		
		NewFullDataSourceDTO dto = new NewFullDataSourceDTO(
				pos,
				dataChecksum, columnGenStepByteArray, dataFormatVersion, dataByteArray,
				lastModifiedUnixDateTime, createdUnixDateTime,
				mappingByteArray, applyToParent,
				minY);
		return dto;
	}
	
	@Override
	public PreparedStatement createInsertStatement(NewFullDataSourceDTO dto) throws SQLException
	{
		String sql =
			"INSERT INTO "+this.getTableName() + " (\n" +
			"   DetailLevel, PosX, PosZ, \n" +
			"   MinY, DataChecksum, \n" +
			"   Data, ColumnGenerationStep, Mapping, \n" +
			"   DataFormatVersion, ApplyToParent, \n" +
			"   LastModifiedUnixDateTime, CreatedUnixDateTime) \n" +
			"VALUES( \n" +
			"    ?, ?, ?, \n" +
			"    ?, ?, \n" +
			"    ?, ?, ?, \n" +
			"    ?, ?, \n" +
			"    ?, ? \n" +
			");";
		PreparedStatement statement = this.createPreparedStatement(sql);
		
		int i = 1;
		statement.setObject(i++, dto.pos.getDetailLevel() - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		statement.setObject(i++, dto.pos.getX());
		statement.setObject(i++, dto.pos.getZ());
		
		statement.setObject(i++, dto.levelMinY);
		statement.setObject(i++, dto.dataChecksum);
		
		statement.setObject(i++, dto.dataByteArray);
		statement.setObject(i++, dto.columnGenStepByteArray);
		statement.setObject(i++, dto.mappingByteArray);
		
		statement.setObject(i++, dto.dataFormatVersion);
		statement.setObject(i++, dto.applyToParent);
		
		statement.setObject(i++, dto.lastModifiedUnixDateTime);
		statement.setObject(i++, dto.createdUnixDateTime);
		
		return statement;
	}
	
	@Override
	public PreparedStatement createUpdateStatement(NewFullDataSourceDTO dto) throws SQLException
	{
		String sql =
			"UPDATE "+this.getTableName()+" \n" +
			"SET \n" +
			"    MinY = ? \n" +
			"   ,DataChecksum = ? \n" +
					
			"   ,Data = ? \n" +
			"   ,ColumnGenerationStep = ? \n" +
			"   ,Mapping = ? \n" +
					
			"   ,DataFormatVersion = ? \n" +
			"   ,ApplyToParent = ? \n" +
					
			"   ,LastModifiedUnixDateTime = ? \n" +
			"   ,CreatedUnixDateTime = ? \n" +
					
			"WHERE DetailLevel = ? AND PosX = ? AND PosZ = ?";
		PreparedStatement statement = this.createPreparedStatement(sql);
		
		int i = 1;
		statement.setObject(i++, dto.levelMinY);
		statement.setObject(i++, dto.dataChecksum);
		
		statement.setObject(i++, dto.dataByteArray);
		statement.setObject(i++, dto.columnGenStepByteArray);
		statement.setObject(i++, dto.mappingByteArray);
		
		statement.setObject(i++, dto.dataFormatVersion);
		statement.setObject(i++, dto.applyToParent);
		
		statement.setObject(i++, dto.lastModifiedUnixDateTime);
		statement.setObject(i++, dto.createdUnixDateTime);
		
		statement.setObject(i++, dto.pos.getDetailLevel() - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		statement.setObject(i++, dto.pos.getX());
		statement.setObject(i++, dto.pos.getZ());
		
		return statement;
	}
	
	
	
	// updates //
	
	public void setApplyToParent(DhSectionPos pos, boolean applyToParent) throws SQLException
	{
		int detailLevel = pos.getDetailLevel() - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
		
		String sql =
			"UPDATE "+this.getTableName()+" \n" +
			"SET ApplyToParent = "+applyToParent+" \n" +
			"WHERE DetailLevel = "+detailLevel+" AND PosX = "+pos.getX()+" AND PosZ = "+pos.getZ();
		
		this.queryDictionaryFirst(sql);
	}
	
	public ArrayList<DhSectionPos> getPositionsToUpdate(int returnCount)
	{
		ArrayList<DhSectionPos> list = new ArrayList<>();
		
		List<Map<String, Object>> resultMapList = this.queryDictionary(
				"select DetailLevel, PosX, PosZ " +
					"from "+this.getTableName()+" " +
					"where ApplyToParent = 1 " +
					"order by DetailLevel asc LIMIT "+returnCount+";");
		
		for (Map<String, Object> resultMap : resultMapList)
		{
			byte detailLevel = (Byte) resultMap.get("DetailLevel");
			byte sectionDetailLevel = (byte) (detailLevel + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
			int posX = (Integer) resultMap.get("PosX");
			int posZ = (Integer) resultMap.get("PosZ");
			
			DhSectionPos pos = new DhSectionPos(sectionDetailLevel, posX, posZ);
			list.add(pos);
		}
		
		return list;
	}
	
	
	
}
