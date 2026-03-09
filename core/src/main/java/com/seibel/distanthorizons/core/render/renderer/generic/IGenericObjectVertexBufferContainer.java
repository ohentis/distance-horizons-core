package com.seibel.distanthorizons.core.render.renderer.generic;

import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;

import java.util.List;

public interface IGenericObjectVertexBufferContainer extends AutoCloseable
{
	void uploadDataToGpu();
	
	void updateVertexData(List<DhApiRenderableBox> uploadBoxList);
	
	EState getState();
	void setState(EState state);
	
	@Override
	void close();
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	enum EState
	{
		NEW,
		UPDATING_DATA,
		READY_TO_UPLOAD,
		RENDER,
		
		ERROR,
	}
	
	//endregion
	
	
	
}
