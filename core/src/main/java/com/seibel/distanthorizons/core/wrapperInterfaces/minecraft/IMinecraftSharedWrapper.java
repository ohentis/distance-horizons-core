package com.seibel.distanthorizons.core.wrapperInterfaces.minecraft;

import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;

import java.io.File;

//TODO: Maybe have IMCClientWrapper & IMCDedicatedWrapper extend this interface???
public interface IMinecraftSharedWrapper extends IBindable
{
	boolean isDedicatedServer();
	
	File getInstallationDirectory();
	
}
