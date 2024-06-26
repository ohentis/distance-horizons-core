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

import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.sql.dto.ChunkHashDTO;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import org.apache.logging.log4j.Logger;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

public class ChunkHashRepo extends AbstractDhRepo<DhChunkPos, ChunkHashDTO>
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public ChunkHashRepo(String databaseType, String databaseLocation) throws SQLException
	{
		super(databaseType, databaseLocation, ChunkHashDTO.class);
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override 
	public String getTableName() { return "ChunkHash"; }
	
	@Override
	public String createWhereStatement(DhChunkPos pos) { return "ChunkPosX = '"+pos.x+"' AND ChunkPosZ = '"+pos.z+"'"; }
	
	
	
	//=======================//
	// repo required methods //
	//=======================//
	
	@Override 
	public ChunkHashDTO convertDictionaryToDto(Map<String, Object> objectMap) throws ClassCastException
	{
		int posX = (Integer) objectMap.get("ChunkPosX");
		int posZ = (Integer) objectMap.get("ChunkPosZ");
		
		int chunkHash = (Integer) objectMap.get("ChunkHash");
		
		
		ChunkHashDTO dto = new ChunkHashDTO(new DhChunkPos(posX, posZ), chunkHash);
		return dto;
	}
	
	@Override
	public PreparedStatement createInsertStatement(ChunkHashDTO dto) throws SQLException
	{
		String sql =
			"INSERT INTO "+this.getTableName() + " (\n" +
			"   ChunkPosX, ChunkPosZ, \n" +
			"   ChunkHash, \n" +
			"   LastModifiedUnixDateTime, CreatedUnixDateTime) \n" +
			"VALUES( \n" +
			"    ?, ?, \n" +
			"    ?, \n" +
			"    ?, ? \n" +
			");";
		PreparedStatement statement = this.createPreparedStatement(sql);
		
		int i = 1;
		statement.setObject(i++, dto.pos.x);
		statement.setObject(i++, dto.pos.z);
		
		statement.setObject(i++, dto.chunkHash);
		
		statement.setObject(i++, System.currentTimeMillis()); // last modified unix time
		statement.setObject(i++, System.currentTimeMillis()); // created unix time
		
		return statement;
	}
	
	@Override
	public PreparedStatement createUpdateStatement(ChunkHashDTO dto) throws SQLException
	{
		String sql =
			"UPDATE "+this.getTableName()+" \n" +
			"SET \n" +
			"    ChunkHash = ? \n" +
			"   ,LastModifiedUnixDateTime = ? \n" +
			"WHERE ChunkPosX = ? AND ChunkPosZ = ?";
		PreparedStatement statement = this.createPreparedStatement(sql);
		
		int i = 1;
		statement.setObject(i++, dto.chunkHash);
		statement.setObject(i++, System.currentTimeMillis()); // last modified unix time
		
		statement.setObject(i++, dto.pos.x);
		statement.setObject(i++, dto.pos.z);
		
		return statement;
	}
	
	
	
}
