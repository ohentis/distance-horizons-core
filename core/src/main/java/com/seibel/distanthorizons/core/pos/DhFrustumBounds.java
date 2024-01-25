package com.seibel.distanthorizons.core.pos;

import org.joml.Math;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class DhFrustumBounds
{
	private final Vector3f boundsMin = new Vector3f();
	private final Vector3f boundsMax = new Vector3f();
	
	
	public DhFrustumBounds(Matrix4f matViewProjectionInv)
	{
		matViewProjectionInv.frustumAabb(this.boundsMin, this.boundsMax);
	}
	
	public boolean Intersects(DhLodPos lodBounds)
	{
		// TODO
		float worldMinY = 0f;
		float worldMaxY = 0f;
		
		int lodPosX = lodBounds.getX().toBlockWidth();
		int lodPosZ = lodBounds.getZ().toBlockWidth();
		int lodSize = lodBounds.getBlockWidth();
		
		Vector3f lodMin = new Vector3f(lodPosX, worldMinY, lodPosZ);
		Vector3f lodMax = new Vector3f(lodPosX + lodSize, worldMaxY, lodPosZ + lodSize);
		
		if (lodMax.x < boundsMin.x || lodMin.x > boundsMax.x) return false;
		//if (lodMax.y < boundsMin.y || lodMin.y > boundsMax.y) return false;
		if (lodMax.z < boundsMin.z || lodMin.z > boundsMax.z) return false;
		
		return true;
	}
}
