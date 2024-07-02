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

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.io.File;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BeaconBeamRepo extends AbstractDhRepo<DhBlockPos, BeaconBeamDTO>
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public BeaconBeamRepo(String databaseType, File databaseFile) throws SQLException
	{
		super(databaseType, databaseFile, BeaconBeamDTO.class);
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override 
	public String getTableName() { return "BeaconBeam"; }
	
	@Override
	public String createWhereStatement(DhBlockPos pos) { return "BlockPosX = "+pos.x+" AND BlockPosY = "+pos.y+" AND BlockPosZ = "+pos.z; }
	
	
	
	//=======================//
	// repo required methods //
	//=======================//
	
	@Override 
	public BeaconBeamDTO convertDictionaryToDto(Map<String, Object> objectMap) throws ClassCastException
	{
		int posX = (Integer) objectMap.get("BlockPosX");
		int posY = (Integer) objectMap.get("BlockPosY");
		int posZ = (Integer) objectMap.get("BlockPosZ");
		
		int red = (Integer) objectMap.get("ColorR");
		int green = (Integer) objectMap.get("ColorG");
		int blue = (Integer) objectMap.get("ColorB");
		
		
		BeaconBeamDTO dto = new BeaconBeamDTO(new DhBlockPos(posX, posY, posZ), new Color(red, green, blue));
		return dto;
	}
	
	@Override
	public PreparedStatement createInsertStatement(BeaconBeamDTO dto) throws SQLException
	{
		String sql =
			"INSERT INTO "+this.getTableName() + " (\n" +
			"   BlockPosX, BlockPosY, BlockPosZ, \n" +
			"   ColorR, ColorG, ColorB, \n" +
			"   LastModifiedUnixDateTime, CreatedUnixDateTime) \n" +
			"VALUES( \n" +
			"    ?, ?, ?, \n" +
			"    ?, ?, ?, \n" +
			"    ?, ? \n" +
			");";
		PreparedStatement statement = this.createPreparedStatement(sql);
		
		int i = 1;
		statement.setObject(i++, dto.pos.x);
		statement.setObject(i++, dto.pos.y);
		statement.setObject(i++, dto.pos.z);
		
		statement.setObject(i++, dto.color.getRed());
		statement.setObject(i++, dto.color.getGreen());
		statement.setObject(i++, dto.color.getBlue());
		
		statement.setObject(i++, System.currentTimeMillis()); // last modified unix time
		statement.setObject(i++, System.currentTimeMillis()); // created unix time
		
		return statement;
	}
	
	@Override
	public PreparedStatement createUpdateStatement(BeaconBeamDTO dto) throws SQLException
	{
		String sql =
			"UPDATE "+this.getTableName()+" \n" +
			"SET \n" +
			"    ColorR = ?, ColorG = ?, ColorB = ?,  \n" +
			"   ,LastModifiedUnixDateTime = ? \n" +
			"WHERE BlockPosX = ? AND BlockPosY = ? AND BlockPosZ = ?";
		PreparedStatement statement = this.createPreparedStatement(sql);
		
		int i = 1;
		statement.setObject(i++, dto.color.getRed());
		statement.setObject(i++, dto.color.getGreen());
		statement.setObject(i++, dto.color.getBlue());
		
		statement.setObject(i++, System.currentTimeMillis()); // last modified unix time
		
		statement.setObject(i++, dto.pos.x);
		statement.setObject(i++, dto.pos.y);
		statement.setObject(i++, dto.pos.z);
		
		return statement;
	}
	
	
	
	//====================//
	// additional methods //
	//====================//
	
	public List<BeaconBeamDTO> getAllBeamsForSectionPos(long pos)
	{
		int minBlockX = DhSectionPos.getMinCornerBlockX(pos);
		int minBlockZ = DhSectionPos.getMinCornerBlockZ(pos);
		int maxBlockX = minBlockX + DhSectionPos.getBlockWidth(pos);
		int maxBlockZ = minBlockZ + DhSectionPos.getBlockWidth(pos);
		
		
		List<Map<String, Object>> objectMapList = this.queryDictionary(
				"SELECT * " +
					"FROM "+this.getTableName()+" " +
					"WHERE " +
						"BlockPosX >= "+minBlockX+" AND BlockPosX <= "+maxBlockX+" " +
						"AND BlockPosZ >= "+minBlockZ+" AND BlockPosX <= "+maxBlockZ);
		
		ArrayList<BeaconBeamDTO> beamList = new ArrayList<>();
		for (Map<String, Object> objectMap : objectMapList)
		{
			beamList.add(this.convertDictionaryToDto(objectMap));
		}
		
		return beamList;
	}
	
	
}
