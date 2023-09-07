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

package com.seibel.distanthorizons.core.dataObjects.fullData.loader;

import com.google.common.collect.HashMultimap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataMetaFile;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;

import java.io.IOException;
import java.util.*;

public abstract class AbstractFullDataSourceLoader
{
	
	public static final HashMultimap<Class<? extends IFullDataSource>, AbstractFullDataSourceLoader> loaderRegistry = HashMultimap.create();
	public final Class<? extends IFullDataSource> fullDataSourceClass;
	public static final HashMap<Long, Class<? extends IFullDataSource>> datatypeIdRegistry = new HashMap<>();
	
	public final long datatypeId;
	public final byte[] loaderSupportedVersions;
	
	
	
	public AbstractFullDataSourceLoader(Class<? extends IFullDataSource> fullDataSourceClass, long datatypeId, byte[] loaderSupportedVersions)
	{
		this.datatypeId = datatypeId;
		this.loaderSupportedVersions = loaderSupportedVersions;
		Arrays.sort(loaderSupportedVersions); // sort to allow fast access
		this.fullDataSourceClass = fullDataSourceClass;
		if (datatypeIdRegistry.containsKey(datatypeId) && datatypeIdRegistry.get(datatypeId) != fullDataSourceClass)
		{
			throw new IllegalArgumentException("Loader for datatypeId " + datatypeId + " already registered with different class: "
					+ datatypeIdRegistry.get(datatypeId) + " != " + fullDataSourceClass);
		}
		Set<AbstractFullDataSourceLoader> loaders = loaderRegistry.get(fullDataSourceClass);
		if (loaders.stream().anyMatch(other ->
		{
			// see if any loaderSupportsVersion conflicts with this one
			for (byte otherVer : other.loaderSupportedVersions)
			{
				if (Arrays.binarySearch(loaderSupportedVersions, otherVer) >= 0)
				{
					return true;
				}
			}
			return false;
		}))
		{
			throw new IllegalArgumentException("Loader for class " + fullDataSourceClass + " that supports one of the version in "
					+ Arrays.toString(loaderSupportedVersions) + " already registered!");
		}
		datatypeIdRegistry.put(datatypeId, fullDataSourceClass);
		loaderRegistry.put(fullDataSourceClass, this);
	}
	
	/**
	 * Can return null if any of the requirements aren't met.
	 *
	 * @throws InterruptedException if the loader thread is interrupted, generally happens when the level is shutting down
	 */
	public abstract IFullDataSource loadData(FullDataMetaFile dataFile, DhDataInputStream inputStream, IDhLevel level) throws IOException, InterruptedException;
	
	
	
	public static AbstractFullDataSourceLoader getLoader(long dataTypeId, byte dataVersion)
	{
		return loaderRegistry.get(datatypeIdRegistry.get(dataTypeId)).stream()
				.filter(l -> Arrays.binarySearch(l.loaderSupportedVersions, dataVersion) >= 0)
				.findFirst().orElse(null);
	}
	
	public static AbstractFullDataSourceLoader getLoader(Class<? extends IFullDataSource> clazz, byte dataVersion)
	{
		return loaderRegistry.get(clazz).stream()
				.filter(loader -> Arrays.binarySearch(loader.loaderSupportedVersions, dataVersion) >= 0)
				.findFirst().orElse(null);
	}
	
}
