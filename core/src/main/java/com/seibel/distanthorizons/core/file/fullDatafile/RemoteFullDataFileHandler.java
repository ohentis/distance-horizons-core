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

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.CompleteFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.generation.IWorldGenerationQueue;
import com.seibel.distanthorizons.core.level.DhLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState;
import com.seibel.distanthorizons.core.network.exceptions.InvalidLevelException;
import com.seibel.distanthorizons.core.network.exceptions.InvalidSectionPosException;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.messages.fullData.generation.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.generation.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.updates.FullDataChangeSummaryRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.updates.FullDataChangeSummaryResponseMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import io.netty.channel.ChannelException;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.annotation.CheckForNull;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class RemoteFullDataFileHandler extends GeneratedFullDataFileHandler implements IDebugRenderable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	@CheckForNull
	private final ClientNetworkState networkState;
	
	private final Set<DhSectionPos> visitedSections = ConcurrentHashMap.newKeySet();
	private final ConcurrentMap<DhSectionPos, FullDataMetaFile> sectionsToUpdate = new ConcurrentHashMap<>();
	private final AtomicBoolean isUpdating = new AtomicBoolean(false);
	
	private final F3Screen.NestedMessage f3Message = new F3Screen.NestedMessage(this::f3Log);
	private final AtomicInteger finishedRequests = new AtomicInteger();
	private final AtomicInteger failedRequests = new AtomicInteger();
	
	public RemoteFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure, @Nullable File saveDirOverride, @Nullable ClientNetworkState networkState)
	{
		super(level, saveStructure, saveDirOverride);
		this.networkState = networkState;
		DebugRenderer.register(this, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue);
	}
	
	@Override
	public FullDataMetaFile getFileIfExist(DhSectionPos pos)
	{
		if (this.networkState == null || !this.fileExists(pos))
			return super.getFileIfExist(pos);
		
		if (!this.networkState.config.postRelogUpdateEnabled)
			return super.getFileIfExist(pos);
		
		FullDataMetaFile metaFile = super.getFileIfExist(pos);
		if (metaFile == null)
			return null;
		
		LOGGER.debug("Checking server updates for section {}", pos);
		pos.forEachChildAtLevel(DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL, childPos ->
		{
			FullDataMetaFile childMetaFile = super.getFileIfExist(childPos);
			if (childMetaFile != null && childMetaFile.baseMetaData != null && visitedSections.add(childPos))
				sectionsToUpdate.put(childPos, childMetaFile);
		});
		sendUpdateChecks();
		
		return metaFile;
	}
	
	private void sendUpdateChecks()
	{
		assert this.networkState != null;
		
		if (this.sectionsToUpdate.isEmpty())
			return;
		
		if (this.isUpdating.getAndSet(true))
			return;
		
		Map<DhSectionPos, Integer> block = sectionsToUpdate.entrySet().stream()
				.limit(20)
				.collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().baseMetaData.checksum));
		for (DhSectionPos pos : block.keySet())
			sectionsToUpdate.remove(pos);
		
		this.networkState.getClient().sendRequest(new FullDataChangeSummaryRequestMessage(level.getLevelWrapper(), block), FullDataChangeSummaryResponseMessage.class)
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
							sendUpdateRequest(pos);
					}
					catch (InvalidLevelException ignored)
					{
						// We're too late
					}
					catch (InvalidSectionPosException ignored)
					{
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
	
	public void sendUpdateRequest(DhSectionPos sectionPos)
	{
		assert this.networkState != null;
		
		this.networkState.getClient().sendRequest(new FullDataSourceRequestMessage(level.getLevelWrapper(), sectionPos, true), FullDataSourceResponseMessage.class)
				.handleAsync((response, throwable) ->
				{
					try
					{
						if (throwable != null)
							throw throwable;
						
						CompleteFullDataSource fullDataSource = response.getFullDataSource(sectionPos, level);
						
						fullDataSource.splitIntoChunkSizedAccessors(((DhLevel) level)::saveWrites);
						response.getFullDataSourceLoader().returnPooledDataSource(fullDataSource);
					}
					catch (InvalidLevelException ignored)
					{
						// We're too late
					}
					catch (ChannelException | RateLimitedException ignored)
					{
						// Can't bother retrying
						this.failedRequests.incrementAndGet();
					}
					catch (Throwable e)
					{
						LOGGER.error("Error while fetching full data source", e);
						this.failedRequests.incrementAndGet();
					}
					
					return null;
				});
	}
	
	private String[] f3Log()
	{
		if (this.networkState == null || !this.networkState.config.postRelogUpdateEnabled)
			return new String[0];
		
		// These metrics are not precise; Updated sections[2] is within range of 1 rate limit or so
		ArrayList<String> lines = new ArrayList<>();
		lines.add("Post-relog update ["+level.getLevelWrapper().getDimensionType().getDimensionName()+"]");
		lines.add("Visited sections: "+visitedSections.size());
		lines.add("Updated sections: "+this.finishedRequests+" / "+(this.sectionsToUpdate.size() + this.finishedRequests.get())+" (failed: "+this.failedRequests+")");
		return lines.toArray(new String[0]);
	}
	
	@Override
	public void debugRender(DebugRenderer r)
	{
		for (Map.Entry<DhSectionPos, FullDataMetaFile> mapEntry : sectionsToUpdate.entrySet())
		{
			r.renderBox(new DebugRenderer.Box(mapEntry.getKey(), -32f, 64f, 0.05f, Color.pink));
		}
	}
	
	@Override
	public void close()
	{
		f3Message.close();
		DebugRenderer.unregister(this, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue);
		super.close();
	}
	
}
