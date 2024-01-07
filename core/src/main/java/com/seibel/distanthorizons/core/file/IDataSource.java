package com.seibel.distanthorizons.core.file;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.loader.AbstractFullDataSourceLoader;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.IBaseDTO;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;

import java.io.IOException;

/**
 * Base for all data sources.
 * 
 * @param <TDhLevel> what type of level this data source can be created from
 */
public interface IDataSource<TDhLevel extends IDhLevel> extends IBaseDTO
{
	
	DhSectionPos getSectionPos();
	@Override
	default String getPrimaryKeyString() { return this.getSectionPos().serialize(); }
	
	
	
	//===============//
	// file handling //
	//===============//
	
	void update(ChunkSizedFullDataAccessor chunkData, TDhLevel level);
	
	void writeToStream(DhDataOutputStream outputStream, TDhLevel level) throws IOException;
	
	
	
	//===========//
	// meta data //
	//===========//
	
	/** Returns the detail level of the data contained by this {@link IFullDataSource}. */
	byte getDataDetailLevel();
	EDhApiWorldGenerationStep getWorldGenStep();
	/**
	 * Returns the name of this data source. <br>
	 * Primarily by {@link AbstractFullDataSourceLoader#getLoader(String, byte)} to determine how to parse
	 * the binary data when read from file.
	 */
	String getDataTypeName();
	/** Defines how the binary data is formatted and which {@link AbstractFullDataSourceLoader} should be used when loading from file. */
	byte getDataFormatVersion();
	
}
