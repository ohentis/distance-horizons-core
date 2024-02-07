package com.seibel.distanthorizons.core.pos;

import org.jetbrains.annotations.NotNull;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;


public class DhFrustumBounds
{
	private final FrustumIntersection frustum;
	private final Vector3f boundsMin = new Vector3f();
	private final Vector3f boundsMax = new Vector3f();
	public float worldMinY;
	public float worldMaxY;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public DhFrustumBounds()
	{
		this.frustum = new FrustumIntersection();
	}
	
	
	
	//=========//
	// methods //
	//=========//
	
	public void updateFrustum(Matrix4fc matWorldViewProjection)
	{
		this.frustum.set(matWorldViewProjection);
		
		Matrix4fc matWorldViewProjectionInv = new Matrix4f(matWorldViewProjection).invert();
		matWorldViewProjectionInv.frustumAabb(this.boundsMin, this.boundsMax);
	}
	
	/** returns true if the LOD bounds intersect the frustum **/
	public boolean intersects(@NotNull DhLodPos lodBounds)
	{
		int lodPosX = lodBounds.getX().toBlockWidth();
		int lodPosZ = lodBounds.getZ().toBlockWidth();
		int lodSize = lodBounds.getBlockWidth();
		
		Vector3f lodMin = new Vector3f(lodPosX, this.worldMinY, lodPosZ);
		Vector3f lodMax = new Vector3f(lodPosX + lodSize, this.worldMaxY, lodPosZ + lodSize);
		
		if (lodMax.x < this.boundsMin.x || lodMin.x > this.boundsMax.x) return false;
		if (lodMax.z < this.boundsMin.z || lodMin.z > this.boundsMax.z) return false;
		if (this.worldMaxY < this.boundsMin.y || this.worldMinY > this.boundsMax.y) return false;
		
		return this.frustum.testAab(lodMin, lodMax);
	}
	
}
