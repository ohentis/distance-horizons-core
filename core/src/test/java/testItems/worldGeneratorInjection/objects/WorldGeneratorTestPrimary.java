package testItems.worldGeneratorInjection.objects;

import com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector;

/**
 * Dummy test implementation object for world generator injection unit tests.
 *
 * @author James Seibel
 * @version 2022-12-5
 */
public class WorldGeneratorTestPrimary extends TestWorldGenerator
{
	public static int PRIORITY = OverrideInjector.DEFAULT_NON_CORE_OVERRIDE_PRIORITY + 5;
	public static final byte SMALLEST_DETAIL_LEVEL = 2;
	
	
	
	@Override
	public int getPriority() { return PRIORITY; }
	
	@Override
	public byte getSmallestDataDetailLevel() { return SMALLEST_DETAIL_LEVEL; }
	
}
