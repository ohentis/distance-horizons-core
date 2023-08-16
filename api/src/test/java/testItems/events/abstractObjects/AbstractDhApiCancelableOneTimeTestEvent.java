package testItems.events.abstractObjects;

import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiCancelableEvent;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiOneTimeEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;

/**
 * A dummy event implementation used for unit testing.
 *
 * @author James Seibel
 * @version 2023-6-23
 */
public abstract class AbstractDhApiCancelableOneTimeTestEvent implements IDhApiCancelableEvent<Boolean>, IDhApiOneTimeEvent<Boolean>
{
	public abstract void onTestEvent(DhApiCancelableEventParam<Boolean> input);
	
	/** just used for testing */
	public abstract Boolean getTestValue();
	
	
	
	//=========================//
	// internal DH API methods //
	//=========================//
	
	@Override
	public final void fireEvent(DhApiCancelableEventParam<Boolean> input)
	{
		this.onTestEvent(input);
		if (input.value)
		{
			input.cancelEvent();
		}
	}
	
}