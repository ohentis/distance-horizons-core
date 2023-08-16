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
