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

import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.generation.IWorldGenerationQueue;
import com.seibel.distanthorizons.core.generation.tasks.IWorldGenTaskTracker;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.multiplayer.ClientNetworkState;
import com.seibel.distanthorizons.core.network.exceptions.InvalidLevelException;
import com.seibel.distanthorizons.core.network.exceptions.InvalidSectionPosException;
import com.seibel.distanthorizons.core.network.messages.fullData.updates.FullDataChangeSummaryRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.updates.FullDataChangeSummaryResponseMessage;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.CheckForNull;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class RemoteFullDataFileHandler extends GeneratedFullDataFileHandler
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	@CheckForNull
	private final ClientNetworkState networkState;

	private final Set<DhSectionPos> visitedSections = ConcurrentHashMap.newKeySet();
	private final ConcurrentMap<DhSectionPos, FullDataMetaFile> sectionsToUpdate = new ConcurrentHashMap<>();
	private final AtomicBoolean isUpdating = new AtomicBoolean(false);
	private boolean invalidSectionsFound = false;
	
	public RemoteFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure, @Nullable ClientNetworkState networkState)
	{
		super(level, saveStructure);
		this.networkState = networkState;
	}
	
	private void sendUpdateChecks()
	{
		assert this.networkState != null;
		
		if (this.invalidSectionsFound)
			this.sectionsToUpdate.clear();
		
		if (this.sectionsToUpdate.isEmpty())
			return;
		
		if (this.isUpdating.getAndSet(true))
			return;
		
		Map<DhSectionPos, Integer> block = sectionsToUpdate.entrySet().stream()
				.limit(20)
				.collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().baseMetaData.checksum));
		for (DhSectionPos pos : block.keySet())
			sectionsToUpdate.remove(pos);
		
		Consumer<ChunkSizedFullDataAccessor> chunkDataConsumer = (ChunkSizedFullDataAccessor data) -> {
			DhSectionPos pos = data.getSectionPos().convertNewToDetailLevel(CompleteFullDataSource.SECTION_SIZE_OFFSET);
			this.writeChunkDataToFile(new DhSectionPos(pos.getDetailLevel(), pos.getX(), pos.getZ()), data);
		};
		
		this.networkState.getClient().<FullDataChangeSummaryResponseMessage>sendRequest(new FullDataChangeSummaryRequestMessage(level.getLevelWrapper(), block))
				.handle((response, throwable) ->
				{
					try
					{
						if (throwable != null)
							throw throwable;
						
						IWorldGenerationQueue queue = this.worldGenQueueRef.get();
						if (queue == null)
							return null;
						
						for (DhSectionPos pos : response.changedPosList)
						{
							queue.submitGenTask(pos, pos.getDetailLevel(), new IWorldGenTaskTracker() {
								@Override
								public boolean isMemoryAddressValid()
								{
									return true;
								}
								
								@NotNull
								@Override
								public Consumer<ChunkSizedFullDataAccessor> getChunkDataConsumer()
								{
									return chunkDataConsumer;
								}
							});
						}
					}
					catch (InvalidLevelException ignored)
					{
						// We're too late
					}
					catch (InvalidSectionPosException e)
					{
						LOGGER.error("Invalid sections found. Updating will not continue.", e);
						invalidSectionsFound = true;
					}
					catch (Throwable e)
					{
						LOGGER.error("Error while checking section updates", e);
					}
					finally
					{
						this.isUpdating.set(false);
						sendUpdateChecks();
					}
					
					return null;
				});
	}
	
	@Override
	public FullDataMetaFile getFileIfExist(DhSectionPos pos)
	{
		// This feature is broken - same data may produce different hashes, apparently
		if (true)
			return super.getFileIfExist(pos);
		
		if (this.networkState == null || !this.isFileUnloaded(pos))
			return super.getFileIfExist(pos);
		
		FullDataMetaFile metaFile = super.getFileIfExist(pos);
		if (metaFile == null)
			return null;
		
		LOGGER.info("Checking server updates for section {}", pos);
		pos.forEachChildAtLevel(DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL, childPos ->
		{
			FullDataMetaFile childMetaFile = super.getFileIfExist(childPos);
			if (childMetaFile != null && visitedSections.add(childPos))
				sectionsToUpdate.put(childPos, childMetaFile);
		});
		sendUpdateChecks();
		
		return metaFile;
	}
}
