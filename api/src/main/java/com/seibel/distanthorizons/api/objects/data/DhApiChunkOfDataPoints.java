package com.seibel.distanthorizons.api.objects.data;

import java.util.List;

public class DhApiChunkOfDataPoints {

	public final int chunkPosX, chunkPosZ;
	public final int chunkBottomY, chunkTopY;
	@SuppressWarnings("unchecked") //generic array.
	private final List<DhApiTerrainDataPoint>[] dataPoints = new List[256];

	public DhApiChunkOfDataPoints(int chunkPosX, int chunkPosZ, int chunkBottomY, int chunkTopY) {
		this.chunkPosX    = chunkPosX;
		this.chunkPosZ    = chunkPosZ;
		this.chunkBottomY = chunkBottomY;
		this.chunkTopY    = chunkTopY;
	}

	public List<DhApiTerrainDataPoint> getDataPoints(int x, int z) {
		return this.dataPoints[(z << 4) | x];
	}

	public void setDataPoints(int x, int z, List<DhApiTerrainDataPoint> dataPoints) {
		this.dataPoints[(z << 4) | x] = dataPoints;
	}
}