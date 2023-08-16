package testItems.singletonInjection.objects;

import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;
import testItems.singletonInjection.interfaces.ISingletonTestOne;
import testItems.singletonInjection.interfaces.ISingletonTestTwo;

/**
 * Dummy test implementation object for dependency injection unit tests.
 *
 * @author James Seibel
 * @version 2022-7-16
 */
public class ConcreteSingletonTestBoth implements ISingletonTestOne, ISingletonTestTwo, IBindable
{
	public static final int VALUE = 3;
	
	@Override
	public void finishDelayedSetup() { }
	
	@Override
	public int getValue()
	{
		return VALUE;
	}
	
	@Override
	public int getDependentValue() { return -1; }
	
}
