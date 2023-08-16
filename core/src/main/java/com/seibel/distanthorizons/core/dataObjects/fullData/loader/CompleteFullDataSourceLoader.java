package com.seibel.distanthorizons.core.dataObjects.fullData.loader;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataMetaFile;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;

import java.io.IOException;

public class CompleteFullDataSourceLoader extends AbstractFullDataSourceLoader
{
	public CompleteFullDataSourceLoader()
	{
		super(CompleteFullDataSource.class, CompleteFullDataSource.TYPE_ID, new byte[]{CompleteFullDataSource.DATA_FORMAT_VERSION});
	}
	
	@Override
	public IFullDataSource loadData(FullDataMetaFile dataFile, DhDataInputStream inputStream, IDhLevel level) throws IOException, InterruptedException
	{
		CompleteFullDataSource dataSource = CompleteFullDataSource.createEmpty(dataFile.pos);
		dataSource.populateFromStream(dataFile, inputStream, level);
		return dataSource;
	}
	
	/** Uses a given stream to create a temporary {@link CompleteFullDataSource}, which is not saved. */
	public CompleteFullDataSource loadData(DhSectionPos pos, DhDataInputStream inputStream, IDhLevel level) throws IOException, InterruptedException
	{
		CompleteFullDataSource dataSource = CompleteFullDataSource.createEmpty(pos);
		dataSource.populateFromStream(null, inputStream, level);
		return dataSource;
	}
}
