package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataFileHandler;

public interface IDhWorldGenLevel extends IDhLevel, GeneratedFullDataFileHandler.IOnWorldGenCompleteListener
{
	void doWorldGen();
	
}
