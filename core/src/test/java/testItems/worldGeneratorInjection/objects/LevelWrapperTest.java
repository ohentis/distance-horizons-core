package testItems.worldGeneratorInjection.objects;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiDimensionTypeWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;

/**
 * Stub implementation of a Level wrapper for basic unit testing.
 *
 * @author James Seibel
 * @version 2022-9-8
 */
public class LevelWrapperTest implements IDhApiLevelWrapper
{
	@Override
	public Object getWrappedMcObject() { return null; }
	
	@Override
	public IDhApiDimensionTypeWrapper getDimensionType() { return null; }
	
	@Override
	public EDhApiLevelType getLevelType() { return null; }
	
	@Override
	public boolean hasCeiling() { return false; }
	
	@Override
	public boolean hasSkyLight() { return false; }
	
	@Override
	public int getHeight() { return 0; }
	
	@Override
	public int getMinHeight() { return IDhApiLevelWrapper.super.getMinHeight(); }
	
}
