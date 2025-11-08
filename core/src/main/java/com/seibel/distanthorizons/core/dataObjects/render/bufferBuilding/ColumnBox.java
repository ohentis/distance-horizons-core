/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
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

package com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.pooling.PhantomArrayListPool;
import com.seibel.distanthorizons.core.render.LodQuadTree;
import com.seibel.distanthorizons.core.util.ColorUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.PerfRecorder;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.ColumnArrayView;
import com.seibel.distanthorizons.coreapi.util.MathUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;

public class ColumnBox
{
	private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	
	/** 
	 * if the skylight has this value that means
	 * that block position is covered/occluded by an adjacent block/column.
	 */
	private static final byte SKYLIGHT_COVERED = -1;
	
	public static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("Column Box");
	
	
	
	
	//=========//
	// builder //
	//=========//
	
	public static void addBoxQuadsToBuilder(
			LodQuadBuilder builder, IDhClientLevel clientLevel,
			short width, short yHeight,
			short minX, short minY, short minZ,
			int color, byte irisBlockMaterialId, byte skyLight, byte blockLight,
			long topData, long bottomData, ColumnArrayView[] adjData, boolean[] isAdjDataSameDetailLevel)
	{
		//================//
		// variable setup //
		//================//
		
		short maxX = (short) (minX + width);
		short maxY = (short) (minY + yHeight);
		short maxZ = (short) (minZ + width);
		byte skyLightTop = skyLight;
		byte skyLightBot = RenderDataPointUtil.doesDataPointExist(bottomData) ? RenderDataPointUtil.getLightSky(bottomData) : 0;
		
		boolean transparencyEnabled = Config.Client.Advanced.Graphics.Quality.transparency.get().transparencyEnabled;
		boolean fakeOceanFloor = Config.Client.Advanced.Graphics.Quality.transparency.get().fakeTransparencyEnabled;
		
		boolean isTransparent = ColorUtil.getAlpha(color) < 255 && transparencyEnabled;
		boolean overVoid = !RenderDataPointUtil.doesDataPointExist(bottomData);
		boolean isTopTransparent = RenderDataPointUtil.getAlpha(topData) < 255 && transparencyEnabled;
		boolean isBottomTransparent = RenderDataPointUtil.getAlpha(bottomData) < 255 && transparencyEnabled;
		
		// defaulting to a value far below what we can normally render means we
		// don't need to have an additional "is cave culling enabled" check
		int caveCullingMaxY = Integer.MIN_VALUE;
		if (Config.Client.Advanced.Graphics.Culling.enableCaveCulling.get())
		{
			caveCullingMaxY = Config.Client.Advanced.Graphics.Culling.caveCullingHeight.get() - clientLevel.getLevelWrapper().getMinHeight();
		}
		
		
		
		// if there isn't any data below this LOD, make this LOD's color opaque to prevent seeing void through transparent blocks
		// Note: this LOD should still be considered transparent for this method's checks, otherwise rendering bugs may occur
		if (!RenderDataPointUtil.doesDataPointExist(bottomData))
		{
			color = ColorUtil.setAlpha(color, 255);
		}
		
		
		// fake ocean transparency
		if (transparencyEnabled && fakeOceanFloor)
		{
			if (!isTransparent && isTopTransparent && RenderDataPointUtil.doesDataPointExist(topData))
			{
				skyLightTop = (byte) MathUtil.clamp(0, 15 - (RenderDataPointUtil.getYMax(topData) - minY), 15);
				yHeight = (short) (RenderDataPointUtil.getYMax(topData) - minY - 1);
			}
			else if (isTransparent && !isBottomTransparent && RenderDataPointUtil.doesDataPointExist(bottomData))
			{
				minY = (short) (minY + yHeight - 1);
				yHeight = 1;
			}
			
			maxY = (short) (minY + yHeight);
		}
		
		
		
		//==========================//
		// add top and bottom faces //
		//==========================//
		
		boolean skipTop = RenderDataPointUtil.doesDataPointExist(topData) 
				&& (RenderDataPointUtil.getYMin(topData) == maxY) 
				&& !isTopTransparent;
		if (!skipTop)
		{
			builder.addQuadUp(minX, maxY, minZ, width, width, ColorUtil.applyShade(color, MC.getShade(EDhDirection.UP)), irisBlockMaterialId, skyLightTop, blockLight);
		}
		
		boolean skipBottom = RenderDataPointUtil.doesDataPointExist(bottomData) 
				&& (RenderDataPointUtil.getYMax(bottomData) == minY) 
				&& !isBottomTransparent;
		if (!skipBottom)
		{
			builder.addQuadDown(minX, minY, minZ, width, width, ColorUtil.applyShade(color, MC.getShade(EDhDirection.DOWN)), irisBlockMaterialId, skyLightBot, blockLight);
		}
		
		
		
		//========================================//
		// add North, south, east, and west faces //
		//========================================//
		
		// NORTH face
		{
			ColumnArrayView adjCol = adjData[EDhDirection.NORTH.ordinal() - 2]; // TODO can we use something other than ordinal-2?
			boolean adjSameDetailLevel = isAdjDataSameDetailLevel[EDhDirection.NORTH.ordinal() - 2];
			// if the adjacent column is null that generally means the adjacent area hasn't been generated yet
			if (adjCol == null)
			{
				// Add an adjacent face if this is opaque face or transparent over the void.
				if (!isTransparent || overVoid)
				{
					builder.addQuadAdj(EDhDirection.NORTH, minX, minY, minZ, width, yHeight, color, irisBlockMaterialId, LodUtil.MAX_MC_LIGHT, blockLight);
				}
			}
			else
			{
				makeAdjVerticalQuad(builder, adjCol, adjSameDetailLevel, caveCullingMaxY, EDhDirection.NORTH, minX, minY, minZ, width, yHeight,
						color, irisBlockMaterialId, blockLight);
			}
		}
		
		// SOUTH face
		{
			ColumnArrayView adjCol = adjData[EDhDirection.SOUTH.ordinal() - 2];
			boolean adjSameDetailLevel = isAdjDataSameDetailLevel[EDhDirection.SOUTH.ordinal() - 2];
			if (adjCol == null)
			{
				if (!isTransparent || overVoid)
				{
					builder.addQuadAdj(EDhDirection.SOUTH, minX, minY, maxZ, width, yHeight, color, irisBlockMaterialId, LodUtil.MAX_MC_LIGHT, blockLight);
				}
			}
			else
			{
				makeAdjVerticalQuad(builder, adjCol, adjSameDetailLevel, caveCullingMaxY, EDhDirection.SOUTH, minX, minY, maxZ, width, yHeight,
						color, irisBlockMaterialId, blockLight);
			}
		}
		
		// WEST face
		{
			ColumnArrayView adjCol = adjData[EDhDirection.WEST.ordinal() - 2];
			boolean adjSameDetailLevel = isAdjDataSameDetailLevel[EDhDirection.WEST.ordinal() - 2];
			if (adjCol == null)
			{
				if (!isTransparent || overVoid)
				{
					builder.addQuadAdj(EDhDirection.WEST, minX, minY, minZ, width, yHeight, color, irisBlockMaterialId, LodUtil.MAX_MC_LIGHT, blockLight);
				}
			}
			else
			{
				makeAdjVerticalQuad(builder, adjCol, adjSameDetailLevel, caveCullingMaxY, EDhDirection.WEST, minX, minY, minZ, width, yHeight,
						color, irisBlockMaterialId, blockLight);
			}
		}
		
		// EAST face
		{
			ColumnArrayView adjCol = adjData[EDhDirection.EAST.ordinal() - 2];
			boolean adjSameDetailLevel = isAdjDataSameDetailLevel[EDhDirection.EAST.ordinal() - 2];
			if (adjCol == null)
			{
				if (!isTransparent || overVoid)
				{
					builder.addQuadAdj(EDhDirection.EAST, maxX, minY, minZ, width, yHeight, color, irisBlockMaterialId, LodUtil.MAX_MC_LIGHT, blockLight);
				}
			}
			else
			{
				makeAdjVerticalQuad(builder, adjCol, adjSameDetailLevel, caveCullingMaxY, EDhDirection.EAST, maxX, minY, minZ, width, yHeight,
						color, irisBlockMaterialId, blockLight);
			}
		}
	}
	
	private static void makeAdjVerticalQuad(
			LodQuadBuilder builder, @NotNull ColumnArrayView adjColumnView, boolean adjacentIsSameDetailLevel, int caveCullingMaxY, EDhDirection direction,
			short x, short yMin, short z, short horizontalWidth, short ySize,
			int color, byte irisBlockMaterialId, byte blockLight)
	{
		//==================//
		// create face with //
		// no adjacent data //
		//==================//
		
		color = ColorUtil.applyShade(color, MC.getShade(direction));
		
		if (adjColumnView.size == 0
			|| RenderDataPointUtil.hasZeroHeight(adjColumnView.get(0)))
		{
			builder.addQuadAdj(direction, x, yMin, z, horizontalWidth, ySize, color, irisBlockMaterialId, LodUtil.MAX_MC_LIGHT, blockLight);
			return;
		}
		
		
		
		//===========================//
		// Build Y-range segments    //
		// with their sky light      //
		//===========================//
		
		boolean transparencyEnabled = Config.Client.Advanced.Graphics.Quality.transparency.get().transparencyEnabled;
		boolean inputTransparent = ColorUtil.getAlpha(color) < 255 && transparencyEnabled;
		short yMax = (short) (yMin + ySize);
		
		// List to store segments: [startY, endY, skyLight]
		ArrayList<YSegment> segments = new ArrayList<>();
		
		int adjCount = adjColumnView.size();
		
		// Start with the entire range at max light
		segments.add(new YSegment(yMin, yMax, LodUtil.MAX_MC_LIGHT));
		
		// Process each adjacent datapoint and split/update segments
		for (int adjIndex = 0; adjIndex < adjCount; adjIndex++)
		{
			long adjPoint = adjColumnView.get(adjIndex);
			short adjMinY = RenderDataPointUtil.getYMin(adjPoint);
			short adjMaxY = RenderDataPointUtil.getYMax(adjPoint);
			
			if (!RenderDataPointUtil.doesDataPointExist(adjPoint)
				|| RenderDataPointUtil.hasZeroHeight(adjPoint)
				|| yMax <= adjMinY)
			{
				continue;
			}
			
			long adjAbovePoint = (adjIndex != 0) ? adjColumnView.get(adjIndex - 1) : RenderDataPointUtil.EMPTY_DATA;
			long adjBelowPoint = (adjIndex + 1 < adjCount) ? adjColumnView.get(adjIndex + 1) : RenderDataPointUtil.EMPTY_DATA;
			
			boolean adjOverVoid = !RenderDataPointUtil.doesDataPointExist(adjBelowPoint);
			boolean adjTransparent = !adjOverVoid
					&& RenderDataPointUtil.getAlpha(adjPoint) < 255
					&& transparencyEnabled;
			
			byte adjSkyLight = RenderDataPointUtil.getLightSky(adjPoint);
			byte lightToApply;
			
			if (!adjTransparent)
			{
				// Adjacent is opaque
				boolean adjacentCoversThis =
					!adjacentIsSameDetailLevel
					&& RenderDataPointUtil.getYMax(adjPoint) >= caveCullingMaxY
					&&
					(
						(x == 0 && direction == EDhDirection.WEST)
						|| (z == 0 && direction == EDhDirection.NORTH)
						|| (x == 256 && direction == EDhDirection.EAST)
						|| (z == 256 && direction == EDhDirection.SOUTH)
					);
				
				lightToApply = adjacentCoversThis ? adjSkyLight : SKYLIGHT_COVERED;
			}
			else
			{
				// Adjacent is transparent, use below light
				lightToApply = RenderDataPointUtil.getLightSky(adjBelowPoint);
			}
			
			
			// Apply light to the range [adjMinY, adjMaxY)
			applyLightToRange(segments, adjMinY, adjMaxY, lightToApply);
			
			// Fill overhang area [adjMaxY, adjAboveMinY) with adjSkyLight
			int adjAboveMinY = RenderDataPointUtil.getYMin(adjAbovePoint);
			if (adjMaxY < adjAboveMinY)
			{
				applyLightToRange(segments, adjMaxY, adjAboveMinY, adjSkyLight);
			}
		}
		
		
		
		//=======================//
		// Create vertical faces //
		// from segments         //
		//=======================//
		
		for (YSegment seg : segments)
		{
			tryAddVerticalFaceWithSkyLightToBuilder(
					builder, direction,
					x, z, horizontalWidth,
					color, irisBlockMaterialId, blockLight,
					seg.skyLight, inputTransparent, seg.endY, seg.startY
			);
		}
	}
	
	// Apply a light value to a Y range, splitting segments as needed
	private static void applyLightToRange(ArrayList<YSegment> segments, int rangeStart, int rangeEnd, byte newLight)
	{
		ArrayList<YSegment> newSegments = new ArrayList<>();
		
		for (YSegment seg : segments)
		{
			// No overlap
			if (seg.endY <= rangeStart 
				|| seg.startY >= rangeEnd)
			{
				newSegments.add(seg);
				continue;
			}
			
			// Partial or complete overlap - need to split
			
			// Part before the range
			if (seg.startY < rangeStart)
			{
				newSegments.add(new YSegment(seg.startY, rangeStart, seg.skyLight));
			}
			
			// Overlapping part - take minimum light
			int overlapStart = Math.max(seg.startY, rangeStart);
			int overlapEnd = Math.min(seg.endY, rangeEnd);
			byte minLight = (byte) Math.min(newLight, seg.skyLight);
			newSegments.add(new YSegment(overlapStart, overlapEnd, minLight));
			
			// Part after the range
			if (seg.endY > rangeEnd)
			{
				newSegments.add(new YSegment(rangeEnd, seg.endY, seg.skyLight));
			}
		}
		
		segments.clear();
		segments.addAll(newSegments);
	}
	
	private static void tryAddVerticalFaceWithSkyLightToBuilder(
			LodQuadBuilder builder, EDhDirection direction,
			short x, short z, short horizontalWidth,
			int color, byte irisBlockMaterialId, byte blockLight,
			byte lastSkyLight, boolean inputTransparent, int quadTopY, int quadBottomY
			)
	{
		// invalid positions will have a negative skylight
		if (lastSkyLight < 0)
		{
			return;
		}
		
		// Don't add transparent vertical faces
		// unless the adjacent position is empty.
		// This is done to prevent walls between water blocks in the ocean.
		if (inputTransparent
			&& (lastSkyLight != LodUtil.MAX_MC_LIGHT))
		{
			return;
		}
		
		// don't add negative/empty height faces
		short height = (short) (quadTopY - quadBottomY);
		if (height <= 0)
		{
			return;
		}
		
		builder.addQuadAdj(
				direction, 
				x, (short) quadBottomY, z, 
				horizontalWidth, height, 
				color, irisBlockMaterialId, lastSkyLight, blockLight);
	}
	
	
	
	private static class YSegment
	{
		int startY;
		int endY;
		byte skyLight;

		YSegment(int startY, int endY, byte skyLight)
		{
			this.startY = startY;
			this.endY = endY;
			this.skyLight = skyLight;
		}
	}
	
	
	/**
	 * @see com.seibel.distanthorizons.core.util.FullDataPointUtil
	 */
	private static class YSegmentUtil
	{
		private static final int HEIGHT_WIDTH = Short.SIZE;
		private static final int SKY_LIGHT_WIDTH = Byte.SIZE;
		
		private static final int START_Y_MASK = (int) Math.pow(2, HEIGHT_WIDTH) - 1;
		private static final int END_Y_MASK = (int) Math.pow(2, HEIGHT_WIDTH) - 1;
		private static final int SKY_LIGHT_MASK = (int) Math.pow(2, SKY_LIGHT_WIDTH) - 1;
		
		private static final int START_Y_OFFSET = 0;
		private static final int END_Y_OFFSET = START_Y_OFFSET + HEIGHT_WIDTH;
		private static final int SKY_LIGHT_OFFSET = END_Y_OFFSET + HEIGHT_WIDTH;
		
		
		
		public static long encode(short startY, short endY, byte skyLight)
		{
			long data = 0L;
			data |= (long) (startY & START_Y_MASK) << START_Y_OFFSET;
			data |= (long) (endY & END_Y_MASK) << END_Y_OFFSET;
			data |= (long) (skyLight & SKY_LIGHT_MASK) << SKY_LIGHT_OFFSET;
			return data;
		}
		
		public static short getStartY(long data) { return (short) ((data >> START_Y_OFFSET) & START_Y_MASK); }
		public static short getEndY(long data) { return (short) ((data >> END_Y_OFFSET) & END_Y_MASK); }
		public static short getSkyLight(long data) { return (short) ((data >> SKY_LIGHT_OFFSET) & SKY_LIGHT_MASK); }
		
	}
	
	
	
	
}
