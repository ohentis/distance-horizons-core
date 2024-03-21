package com.seibel.distanthorizons.core.file;

import com.seibel.distanthorizons.api.enums.EDhApiDetailLevel;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.IBaseDTO;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;

import java.io.IOException;

/**
 * Base for all data sources. <br><br>
 * 
 * AutoCloseable Can be implemented to allow for disposing of pooled data sources. <br><br>
 * 
 * @param <TDhLevel> there are times when we need specifically a client level vs a more generic level
 */
public interface IDataSource<TDhLevel extends IDhLevel> extends IBaseDTO<DhSectionPos>, AutoCloseable
{
	DhSectionPos getSectionPos();
	
	
	
	//===============//
	// file handling //
	//===============//
	
	/** @return true if the data was changed */
	boolean update(FullDataSourceV2 chunkData, TDhLevel level);
	
	// still used by RenderSource, remove once that's been changed
	@Deprecated
	void writeToStream(DhDataOutputStream outputStream, TDhLevel level) throws IOException;
	
	
	
	//===========//
	// meta data //
	//===========//
	
	/** 
	 * Returns the detail level of the data contained by this data source. 
	 * IE: 0 for block, 1 for 2x2 blocks, etc.
	 * 
	 * @see EDhApiDetailLevel
	 */
	byte getDataDetailLevel();
	
	/** Defines how the binary data is formatted. */
	byte getDataFormatVersion();
	
}
