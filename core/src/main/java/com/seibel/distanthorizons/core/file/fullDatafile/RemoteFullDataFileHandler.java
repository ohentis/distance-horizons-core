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

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IIncompleteFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.multiplayer.client.FullDataRefreshQueue;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Objects;

public class RemoteFullDataFileHandler extends GeneratedFullDataFileHandler
{
	@Nullable
	private final FullDataRefreshQueue dataRefreshQueue;
	
	public RemoteFullDataFileHandler(IDhClientLevel level, AbstractSaveStructure saveStructure, @Nullable File saveDirOverride, @Nullable FullDataRefreshQueue dataRefreshQueue)
	{
		super(level, saveStructure, saveDirOverride);
		this.dataRefreshQueue = dataRefreshQueue;
	}
	
	@Override
	public IFullDataSource get(DhSectionPos pos)
	{
		IFullDataSource fullDataSource = super.get(pos);
		if (fullDataSource instanceof IIncompleteFullDataSource || this.dataRefreshQueue == null)
		{
			return fullDataSource;
		}
		
		pos.forEachChildAtLevel(DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL, childPos -> {
			Integer checksum = this.repo.getChecksumForSection(childPos);
			if (checksum == null)
			{
				return;
			}
			
			this.dataRefreshQueue.submitRequest(childPos, this.level::updateDataSourcesWithChunkData, checksum);
		});
		
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
