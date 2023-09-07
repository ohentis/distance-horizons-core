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

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;

public interface IIncompleteFullDataSource extends IFullDataSource
{
	/**
	 * Overwrites data in this object with non-null data from the input {@link IFullDataSource}. <br><br>
	 *
	 * This can be used to either merge same sized data sources or downsample to
	 */
	void sampleFrom(IFullDataSource fullDataSource);
	
	/**
	 * Attempts to convert this {@link IIncompleteFullDataSource} into a {@link CompleteFullDataSource}.
	 *
	 * @return a new {@link CompleteFullDataSource} if successful, this if the promotion failed, .
	 */
	IFullDataSource tryPromotingToCompleteDataSource();
	
	boolean hasBeenPromoted();
	
}
