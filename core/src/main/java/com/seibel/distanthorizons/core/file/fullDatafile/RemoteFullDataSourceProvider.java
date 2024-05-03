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

package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.multiplayer.client.FullDataRefreshQueue;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Map;

public class RemoteFullDataSourceProvider extends GeneratedFullDataSourceProvider
{
	@Nullable
	private final FullDataRefreshQueue dataRefreshQueue;
	
	public RemoteFullDataSourceProvider(IDhLevel level, AbstractSaveStructure saveStructure, @Nullable File saveDirOverride, @Nullable FullDataRefreshQueue dataRefreshQueue)
	{
		super(level, saveStructure, saveDirOverride);
		this.dataRefreshQueue = dataRefreshQueue;
	}
	
	@Override
	@Nullable
	public FullDataSourceV2 get(DhSectionPos pos)
	{
		FullDataSourceV2 fullDataSource = super.get(pos);
		if (fullDataSource == null || this.dataRefreshQueue == null)
		{
			return fullDataSource;
		}
		
		Map<DhSectionPos, Long> timestamps = this.getTimestampsForRange(
				pos.getMinCornerPos(DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL),
				pos.getMaxCornerPos(DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL)
		);
		for (Map.Entry<DhSectionPos, Long> entry : timestamps.entrySet())
		{
			this.dataRefreshQueue.submitRequest(entry.getKey(), entry.getValue(), this.delayedFullDataSourceSaveCache::queueDataSourceForUpdateAndSave);
		}
		
		return fullDataSource;
	}
	
	@Override
	public void close()
	{
		if (this.dataRefreshQueue != null)
		{
			this.dataRefreshQueue.close();
		}
		super.close();
	}
	
}