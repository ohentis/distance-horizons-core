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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LegacyFullDataRepo extends AbstractLegacyDataSourceRepo
{
	public static final String TABLE_NAME = "Legacy_FullData_V1";
	
	
	public LegacyFullDataRepo(String databaseType, String databaseLocation) throws SQLException
	{
		super(databaseType, databaseLocation);
	}
	
	
	@Override
	public String getTableName() { return TABLE_NAME; }
	
	@Override 
	public String createWhereStatement(DhSectionPos pos) { return "DhSectionPos = '"+pos.serialize()+"'"; }
	
	
	
	//===========//
	// migration //
	//===========//
	
	/** Returns how many positions need to be migrated over to the new version */
	public int getMigrationCount()
	{
		Map<String, Object> resultMap = this.queryDictionaryFirst(
				"select COUNT(*) as itemCount from "+this.getTableName());
		
		if (resultMap == null)
		{
			return 0;
		}
		else
		{
			int count = (int) resultMap.get("itemCount");
			return count;
		}
	}
	
	/** Returns the new "returnCount" positions that need to be migrated */
	public ArrayList<DhSectionPos> getPositionsToMigrate(int returnCount)
	{
		ArrayList<DhSectionPos> list = new ArrayList<>();
		
		List<Map<String, Object>> resultMapList = this.queryDictionary(
				"select DhSectionPos " +
						"from "+this.getTableName()+" " +
						"LIMIT "+returnCount+";");
		
		for (Map<String, Object> resultMap : resultMapList)
		{
			// returned in the format [sectionDetailLevel,x,z] IE [6,0,0]
			DhSectionPos sectionPos = DhSectionPos.deserialize((String) resultMap.get("DhSectionPos"));
			list.add(sectionPos);
		}
		
		return list;
	}
	
	
	
}
