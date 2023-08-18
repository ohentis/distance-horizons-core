package com.seibel.distanthorizons.core.wrapperInterfaces.config;

import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;

public interface IConfigGui extends IBindable
{
	
	void addOnScreenChangeListener(Runnable newListener);
	void removeOnScreenChangeListener(Runnable oldListener);
	
}
