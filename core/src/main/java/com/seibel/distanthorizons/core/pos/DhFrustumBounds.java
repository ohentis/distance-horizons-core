package com.seibel.distanthorizons.core.pos;

import com.seibel.distanthorizons.coreapi.util.math.Mat4f;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;


public class DhFrustumBounds
{
	private final FrustumIntersection frustum;
	private final Vector3f boundsMin = new Vector3f();
	private final Vector3f boundsMax = new Vector3f();
	private final float worldMinY;
	private final float worldMaxY;
	
	
	public DhFrustumBounds(Matrix4fc matViewProjection, float minY, float maxY)
	{
		this.frustum = new FrustumIntersection();
		this.frustum.set(matViewProjection);
		
		Matrix4fc matViewProjectionInv = new Matrix4f(matViewProjection).invert();
		matViewProjectionInv.frustumAabb(this.boundsMin, this.boundsMax);
		this.worldMinY = minY;
		this.worldMaxY = maxY;
	}
	
	public boolean Intersects(DhLodPos lodBounds)
	{
		int lodPosX = lodBounds.getX().toBlockWidth();
		int lodPosZ = lodBounds.getZ().toBlockWidth();
		int lodSize = lodBounds.getBlockWidth();
		
		Vector3f lodMin = new Vector3f(lodPosX, worldMinY, lodPosZ);
		Vector3f lodMax = new Vector3f(lodPosX + lodSize, worldMaxY, lodPosZ + lodSize);
		
		//if (lodMax.x < this.boundsMin.x || lodMin.x > this.boundsMax.x) return false;
		//if (lodMax.z < this.boundsMin.z || lodMin.z > this.boundsMax.z) return false;
		//if (this.worldMaxY < this.boundsMin.y || this.worldMinY > this.boundsMax.y) return false;
		
		return frustum.testAab(lodMin, lodMax);
	}
}
