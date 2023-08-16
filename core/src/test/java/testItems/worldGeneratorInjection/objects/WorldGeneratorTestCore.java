package testItems.worldGeneratorInjection.objects;

import com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector;

/**
 * Dummy test implementation object for world generator injection unit tests.
 *
 * @author James Seibel
 * @version 2022-12-5
 */
public class WorldGeneratorTestCore extends TestWorldGenerator
{
	public static final int PRIORITY = OverrideInjector.CORE_PRIORITY;
	public static final byte SMALLEST_DETAIL_LEVEL = 1;
	
	
	@Override
	public int getPriority() { return PRIORITY; }
	
	@Override
	public byte getSmallestDataDetailLevel() { return SMALLEST_DETAIL_LEVEL; }
	
}
