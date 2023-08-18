package com.seibel.distanthorizons.api.enums.worldGeneration;

/**
 * EMPTY, <br>
 * STRUCTURE_START, <br>
 * STRUCTURE_REFERENCE, <br>
 * BIOMES, <br>
 * NOISE, <br>
 * SURFACE, <br>
 * CARVERS, <br>
 * LIQUID_CARVERS, <br>
 * FEATURES, <br>
 * LIGHT, <br>
 *
 * @author James Seibel
 * @version 2023-4-20
 * @since API 1.0.0
 */
public enum EDhApiWorldGenerationStep
{
	EMPTY(0),
	STRUCTURE_START(1),
	STRUCTURE_REFERENCE(2),
	BIOMES(3),
	NOISE(4),
	SURFACE(5),
	CARVERS(6),
	LIQUID_CARVERS(7),
	FEATURES(8),
	LIGHT(9);
	
	
	
	/** used when serializing this enum. */
	public final byte value;
	
	EDhApiWorldGenerationStep(int value) { this.value = (byte) value; }
	
	/** @return null if the value doesn't correspond to a {@link EDhApiWorldGenerationStep}. */
	public static EDhApiWorldGenerationStep fromValue(int value)
	{
		for (EDhApiWorldGenerationStep genStep : EDhApiWorldGenerationStep.values())
		{
			if (genStep.value == value)
			{
				return genStep;
			}
		}
		
		return null;
	}
	
}
