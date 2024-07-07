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

package com.seibel.distanthorizons.core.render.renderer.generic;

import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBoxGroupShading;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class CloudRenderHandler
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	private static final String CLOUD_RESOURCE_TEXTURE_PATH = "assets/distanthorizons/textures/clouds.png";
	// FIXME transparency sorting makes having transparent clouds impossible
	private static final Color CLOUD_COLOR = new Color(255,255,255,255);
	
	private static final boolean DEBUG_BORDER_COLORS = false;
	
	/** 
	 * How wide an individual box is. <br>
	 * Measured in blocks.
	 */
	private static final int CLOUD_BOX_WIDTH = 128;
	/** measured in blocks */
	private static final int CLOUD_BOX_THICKNESS = 32;
	
	private final IDhApiRenderableBoxGroup[][] boxGroupByOffset;
	private final IDhLevel level;
	
	private float moveSpeedInBlocksPerSecond = 3.0f;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public CloudRenderHandler(IDhLevel level, GenericObjectRenderer renderer) 
	{
		this.level = level;
		
		
		// default to a single empty slot in case the texture is broken
		boolean[][] cloudLocations = new boolean[1][1];
		try
		{
			cloudLocations = getCloudsFromTexture();
		}
		catch (FileNotFoundException e)
		{
			LOGGER.error(e.getMessage(), e);
		}
		catch (IOException e)
		{
			LOGGER.error("Unexpected issue getting cloud texture, error: ["+e.getMessage()+"].", e);
		}
		
		if (cloudLocations.length != 0 &&
			cloudLocations.length != cloudLocations[0].length)
		{
			LOGGER.warn("Non-square cloud texture found, some parts of the texture will be clipped off.");
		}
		
		
		
		int textureWidth = cloudLocations.length; 
		ArrayList<DhApiRenderableBox> boxList = new ArrayList<>(512);
		for (int x = 0; x < textureWidth; x ++)
		{
			for (int z = 0; z < textureWidth; z ++)
			{
				if (cloudLocations[x][z])
				{
					int minX = x * CLOUD_BOX_WIDTH;
					int minZ = z * CLOUD_BOX_WIDTH;
					int maxX = minX + CLOUD_BOX_WIDTH;
					int maxZ = minZ + CLOUD_BOX_WIDTH;
					
					Color color = CLOUD_COLOR;
					if (DEBUG_BORDER_COLORS)
					{
						// equals is included so the board is 2 blocks wide, it makes it easier to see
						if (x <= 1) { color = Color.RED; }
						else if (x >= textureWidth-2) { color = Color.GREEN; }
						if (z <= 1) { color = Color.BLUE; }
						else if (z >= textureWidth-2) { color = Color.BLACK; }
					}
					
					DhApiRenderableBox box = new DhApiRenderableBox(
							new DhApiVec3f(minX, 0, minZ),
							new DhApiVec3f(maxX, CLOUD_BOX_THICKNESS, maxZ),
							color
					);
					boxList.add(box);
				}
			}
		}
		
		
		// slightly lighter shading than the default
		DhApiRenderableBoxGroupShading cloudShading = DhApiRenderableBoxGroupShading.getUnshaded();
		cloudShading.north = cloudShading.south = 0.9f;
		cloudShading.east = cloudShading.west = 0.8f;
		cloudShading.top = 1.0f;
		cloudShading.bottom = 0.7f;
		
		this.boxGroupByOffset = new IDhApiRenderableBoxGroup[3][3];
		for (int x = -1; x <= 1; x++)
		{
			for (int z = -1; z <= 1; z++)
			{
				IDhApiRenderableBoxGroup boxGroup = GenericRenderObjectFactory.INSTANCE.createRelativePositionedGroup(
						new DhApiVec3f(0, 0, 0), // the offset will be set during rendering
						boxList);
				boxGroup.setBlockLight(LodUtil.MIN_MC_LIGHT);
				boxGroup.setSkyLight(LodUtil.MAX_MC_LIGHT);
				boxGroup.setSsaoEnabled(false);
				boxGroup.setShading(cloudShading);
				
				CloudParams offset = new CloudParams(textureWidth, x, z);
				boxGroup.setPreRenderFunc((renderParam) -> this.preRender(offset));
				
				renderer.add(boxGroup);
				this.boxGroupByOffset[x+1][z+1] = boxGroup;
			}
		}
	}
	
	public void preRender(CloudParams clouds)
	{
		IDhApiRenderableBoxGroup boxGroup = this.boxGroupByOffset[clouds.instanceOffsetX+1][clouds.instanceOffsetZ+1];
		
		boolean renderClouds = Config.Client.Advanced.Graphics.GenericRendering.enableCloudRendering.get();
		boxGroup.setActive(renderClouds);
		if(!renderClouds)
		{
			return;
		}
		
		
		
		//================//
		// cloud movement //
		//================//
		
		long currentTime = System.currentTimeMillis();
		float deltaTime = (currentTime - clouds.lastFrameTime) / 1000.0f; // Delta time in seconds
		clouds.lastFrameTime = currentTime;
		
		float deltaX = this.moveSpeedInBlocksPerSecond * deltaTime;
		// negative delta is to match vanilla's cloud movement
		clouds.xOffset -= deltaX;
		// wrap the cloud around after reaching the edge
		clouds.xOffset %= clouds.widthInBlocks;
		
		
		
		//============================//
		// camera movement and offset //
		//============================//
		
		// camera position
		int cameraPosX = (int)MC_RENDER.getCameraExactPosition().x;
		int cameraPosZ = (int)MC_RENDER.getCameraExactPosition().z;
		// offset the camera position by negative 1 width when below zero to fix off-by-one errors in the negative direction
		if (cameraPosX < 0) { cameraPosX -= clouds.widthInBlocks; }
		if (cameraPosZ < 0) { cameraPosZ -= clouds.widthInBlocks; }
		
		// determine how many cloud instances away from the origin we are
		int cloudInstanceOffsetX = cameraPosX / (int)clouds.widthInBlocks;
		int cloudInstanceOffsetZ = cameraPosZ / (int)clouds.widthInBlocks;
		
		// calculate the new offset
		float xOffset = (cloudInstanceOffsetX * clouds.widthInBlocks);
		float zOffset = (cloudInstanceOffsetZ * clouds.widthInBlocks);
		
		
		
		//==============//
		// update group //
		//==============//
		
		boxGroup.setOriginBlockPos(
				new DhApiVec3f(
					clouds.xOffset + (clouds.instanceOffsetX * clouds.widthInBlocks) + xOffset + clouds.halfWidthInBlocks,
					this.level.getLevelWrapper().getMaxHeight() + 200,
					clouds.zOffset + (clouds.instanceOffsetZ * clouds.widthInBlocks) + zOffset + clouds.halfWidthInBlocks
				)
		);
	}
	
	
	
	//==================//
	// texture handling //
	//==================//
	
	public static boolean[][] getCloudsFromTexture() throws FileNotFoundException, IOException
	{
		final ClassLoader loader = Thread.currentThread().getContextClassLoader();
		
		boolean[][] whitePixels = null;
		try(InputStream imageInputStream = loader.getResourceAsStream(CLOUD_RESOURCE_TEXTURE_PATH))
		{
			if (imageInputStream == null)
			{
				throw new FileNotFoundException("Unable to find cloud texture at resource path: ["+CLOUD_RESOURCE_TEXTURE_PATH+"].");
			}
			
			BufferedImage image = ImageIO.read(imageInputStream);
			
			int width = image.getWidth();
			int height = image.getHeight();
			
			whitePixels = new boolean[width][height];
			
			for (int x = 0; x < width; x ++)
			{
				for (int z = 0; z < width; z ++)
				{
					Color color = new Color(image.getRGB(x,z));
					whitePixels[x][z] = color.equals(Color.WHITE);
				}
			}
		}
		
		return whitePixels;
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	private static class CloudParams
	{
		public final float textureWidth;
		public final float widthInBlocks;
		public final float halfWidthInBlocks;
		
		public final int instanceOffsetX;
		public final int instanceOffsetZ;
		
		
		public float xOffset = 0;
		public float zOffset = 0;
		
		public long lastFrameTime = System.currentTimeMillis();
		
		
		
		// constructor //
		
		public CloudParams(float textureWidth, int instanceOffsetX, int instanceOffsetZ)
		{
			this.textureWidth = textureWidth;
			this.widthInBlocks = (this.textureWidth * CLOUD_BOX_WIDTH);
			this.halfWidthInBlocks = this.widthInBlocks / 2;
			
			this.instanceOffsetX = instanceOffsetX;
			this.instanceOffsetZ = instanceOffsetZ;
		}
		
	}
	
}
