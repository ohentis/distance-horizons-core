package com.seibel.distanthorizons.core.file;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.NewFullDataSource;
import com.seibel.distanthorizons.core.file.fullDatafile.IFullDataSourceProvider;
import com.seibel.distanthorizons.core.file.renderfile.IRenderSourceProvider;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;

import java.util.concurrent.CompletableFuture;

/**
 * Base for all data source providers
 * 
 * @see IFullDataSourceProvider
 * @see IRenderSourceProvider
 */
public interface ISourceProvider<TDataSource extends IDataSource<TDhLevel>, TDhLevel extends IDhLevel> extends AutoCloseable
{
	CompletableFuture<TDataSource> getAsync(DhSectionPos pos);
	
	CompletableFuture<Void> updateDataSourceAsync(NewFullDataSource inputData);
	
}
