package com.seibel.distanthorizons.api.enums.rendering;

/**
 * contains the indices used by Iris to determine how different block types should be rendered
 * 
 * USE_OPTIFINE_FOG_SETTING, <br>
 * FOG_ENABLED, <br>
 * FOG_DISABLED <br>
 * 
 * @author James Seibel
 * @since API 3.0.0
 * @version 2024-7-11
 */
public enum EDhApiBlockMaterial
{
	UNKOWN(0),
	LEAVES(1),
	STONE(2),
	WOOD(3),
	METAL(4),
	DIRT(5),
	LAVA(6),
	DEEPSLATE(7),
	SNOW(8),
	SAND(9),
	TERRACOTTA(10),
	NETHER_STONE(11),
	WATER(12),
	GRASS(13),
	/** shouldn't normally be needed, but just in case */
	AIR(14),
	ILLUMINATED(15); // Max value
	
	
	
	public final byte index;
	
	EDhApiBlockMaterial(int index) { this.index = (byte)index;}
	
	public static EDhApiBlockMaterial getFromIndex(int index)
	{
		for(EDhApiBlockMaterial material : EDhApiBlockMaterial.values())
		{
			if (material.index == index)
			{
				return material;
			}
		}
		
		return EDhApiBlockMaterial.UNKOWN;
	}
	
}
