package com.seibel.distanthorizons.core.render;

import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.ColumnRenderBufferBuilder;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.file.renderfile.ILodRenderSourceProvider;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.Reference;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadTree;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.ColumnRenderBuffer;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A render section represents an area that could be rendered.
 * For more information see {@link LodQuadTree}.
 */
public class LodRenderSection implements IDebugRenderable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	
    public final DhSectionPos pos;
	
	private boolean isRenderingEnabled = false;
	/** 
	 * If this is true, then {@link LodRenderSection#reload(ILodRenderSourceProvider)} was called while 
	 * a {@link ILodRenderSourceProvider} was already being loaded.
	 */
	private boolean reloadRenderSourceOnceLoaded = false;

	private ILodRenderSourceProvider renderSourceProvider = null;
    private CompletableFuture<ColumnRenderSource> renderSourceLoadFuture;
	private ColumnRenderSource renderSource;

	private IDhClientLevel level = null;

	//FIXME: Temp Hack to prevent swapping buffers too quickly
	private long lastNs = -1;
	private long lastSwapLocalVersion = -1;
	private boolean neighborUpdated = false;
	/** 2 sec */
	private static final long SWAP_TIMEOUT_IN_NS = 2_000000000L;
	/** 1 sec */
	private static final long SWAP_BUSY_COLLISION_TIMEOUT_IN_NS = 1_000000000L;

	private CompletableFuture<ColumnRenderBuffer> buildRenderBufferFuture = null;
	private final Reference<ColumnRenderBuffer> inactiveRenderBufferRef = new Reference<>();

	/** a reference is used so the render buffer can be swapped to and from the buffer builder */
	public final AtomicReference<ColumnRenderBuffer> activeRenderBufferRef = new AtomicReference<>();
	private volatile boolean doDisposeActiveBuffer = false;

	private final QuadTree<LodRenderSection> parentQuadTree;
	
    public LodRenderSection(QuadTree<LodRenderSection> parentQuadTree, DhSectionPos pos) {
		this.pos = pos;
		this.parentQuadTree = parentQuadTree;

		DebugRenderer.register(this);
	}

	public void debugRender(DebugRenderer r)
	{
		Color color = Color.red;

		if (this.renderSourceProvider == null) color = Color.black;

		if (this.renderSourceLoadFuture != null) color = Color.yellow;

		if (renderSource != null) {
			color = Color.blue;
			if (buildRenderBufferFuture != null) color = Color.magenta;
			if (canRenderNow()) color = Color.cyan;
			if (canRenderNow() && isRenderingEnabled) color = Color.green;
		}

		r.renderBox(new DebugRenderer.Box(this.pos, 400, 8f, Objects.hashCode(this), 0.1f, color));
	}
	
	
	
	//===========//
	// rendering //
	//===========//
	
	public void enableRendering() {
		this.isRenderingEnabled = true;
	}
	public void disableRendering() {
		this.isRenderingEnabled = false;
	}
	
	//=============//
	// render data //
	//=============//

	private void startLoadRenderSource() {
		this.renderSourceLoadFuture = this.renderSourceProvider.readAsync(this.pos);
		this.renderSourceLoadFuture.whenComplete((renderSource, ex) ->
		{
			this.renderSourceLoadFuture = null;
			this.renderSource = renderSource;
			this.lastNs = -1;
			markBufferDirty();
			if (this.reloadRenderSourceOnceLoaded)
			{
				this.reloadRenderSourceOnceLoaded = false;
				reload(this.renderSourceProvider);
			}
		});
	}
	
	/** does nothing if a render source is already loaded or in the process of loading */
	public void loadRenderSource(ILodRenderSourceProvider renderDataProvider, IDhClientLevel level)
	{
		this.renderSourceProvider = renderDataProvider;
		this.level = level;
		if (this.renderSourceProvider == null)
		{
			return;
		}
		// don't re-load or double load the render source
		if (this.renderSource != null || this.renderSourceLoadFuture != null)
		{
			return;
		}
		startLoadRenderSource();
	}
	
    public void reload(ILodRenderSourceProvider renderDataProvider)
	{
		if (pos.sectionDetailLevel == DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL)
			DebugRenderer.makeParticle(
					new DebugRenderer.BoxParticle(
							new DebugRenderer.Box(pos, 0, 256f, 0.03f, Color.cyan),
							0.5, 512f
					)
			);
		this.renderSourceProvider = renderDataProvider;
		if (this.renderSourceProvider == null)
		{
			return;
		}
		// don't accidentally enable rendering for a disabled section
		if (!this.isRenderingEnabled)
		{
			return;
		}
		// wait for the current load future to finish before re-loading
		if (this.renderSourceLoadFuture != null)
		{
			reloadRenderSourceOnceLoaded = true;
			return;
		}
		startLoadRenderSource();
	}
	

	
	
	
	//========================//
	// getters and properties //
	//========================//

	/** This can return true before the render data is loaded */
    public boolean isRenderingEnabled() { return this.isRenderingEnabled; }
	
    public ColumnRenderSource getRenderSource() { return this.renderSource; }
	
	public boolean canRenderNow()
	{
		return this.renderSource != null
				&&
				(
					(
						// if true; either this section represents empty chunks or un-generated chunks. 
						// Either way, there isn't any data to render, but this should be considered "loaded"
						this.renderSource.isEmpty()
					)
					||
					(
						// check if the buffers have been loaded
						this.activeRenderBufferRef.get() != null
					)
				);
	}

	//================//
	// Render Methods //
	//================//
	private void cancelBuildBuffer()
	{
		if (this.buildRenderBufferFuture != null)
		{
			//LOGGER.info("Cancelling build of render buffer for {}", sectionPos);
			this.buildRenderBufferFuture.cancel(true);
			this.buildRenderBufferFuture = null;
		}
	}

	private boolean isBufferOutdated() {
		//if (this.lastNs == -1) return false;
/*		boolean inTimeout = System.nanoTime() - this.lastNs < SWAP_TIMEOUT_IN_NS;
		if (!inTimeout && ColumnRenderBufferBuilder.isBusy()) {
			this.lastNs += (long) (SWAP_BUSY_COLLISION_TIMEOUT_IN_NS * Math.random());
			return true;
		}*/
		return neighborUpdated || renderSource.localVersion.get() - lastSwapLocalVersion > 0;
	}

	private LodRenderSection[] getNeighbors()
	{
		LodRenderSection[] adjacents = new LodRenderSection[EDhDirection.ADJ_DIRECTIONS.length];
		for (EDhDirection direction : EDhDirection.ADJ_DIRECTIONS) {
			try {
				DhSectionPos adjPos = pos.getAdjacentPos(direction);
				LodRenderSection adjRenderSection = parentQuadTree.getValue(adjPos);
				// adjacent render sources might be null
				adjacents[direction.ordinal() - 2] = adjRenderSection;
			} catch (IndexOutOfBoundsException e) {
				// adjacent positions can be out of bounds, in that case a null render source will be used
			}
		}
		return adjacents;
	}

	private void tellNeighborsUpdated()
	{
		LodRenderSection[] adjacents = getNeighbors();
		for (LodRenderSection adj : adjacents) {
			if (adj != null) {
				adj.neighborUpdated = true;
			}
		}
	}

	/** @return true if this section is loaded and set to render */
	public boolean canBuildBuffer() { return this.renderSource != null && this.buildRenderBufferFuture == null && !this.renderSource.isEmpty() && isBufferOutdated(); }

	/** @return true if this section is loaded and set to render */
	public boolean canSwapBuffer() { return this.buildRenderBufferFuture != null && this.buildRenderBufferFuture.isDone(); }



	/**
	 * Try and swap in new render buffer for this section. Note that before this call, there should be no other
	 * places storing or referencing the render buffer.
	 * @return True if the swap was successful. False if swap is not needed or if it is in progress.
	 */
	public boolean tryBuildAndSwapBuffer()
	{
		if (doDisposeActiveBuffer && this.activeRenderBufferRef.get() != null) {
			doDisposeActiveBuffer = false;
			this.activeRenderBufferRef.getAndSet(null).close();
			return false;
		}
		boolean didSwapped = false;
		if (canBuildBuffer()) {
			//if (false)
			if (pos.sectionDetailLevel == DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL)
				DebugRenderer.makeParticle(
						new DebugRenderer.BoxParticle(
								new DebugRenderer.Box(pos, 32f, 64f, 0.2f, Color.yellow),
								0.5, 16f
						)
				);
			neighborUpdated = false;
			long newVs = renderSource.localVersion.get();
			if (lastSwapLocalVersion != newVs) {
				lastSwapLocalVersion = newVs;
				tellNeighborsUpdated();
			}
			LodRenderSection[] adjacents = getNeighbors();
			ColumnRenderSource[] adjacentSources =  new ColumnRenderSource[EDhDirection.ADJ_DIRECTIONS.length];
			for (int i = 0; i < EDhDirection.ADJ_DIRECTIONS.length; i++) {
				LodRenderSection adj = adjacents[i];
				if (adj != null) {
					adjacentSources[i] = adj.getRenderSource();
				}
			}
			this.buildRenderBufferFuture = ColumnRenderBufferBuilder.buildBuffers(level, this.inactiveRenderBufferRef, renderSource, adjacentSources);
		}
		if (canSwapBuffer()) {
			this.lastNs = System.nanoTime();
			ColumnRenderBuffer newBuffer;
			try {
				newBuffer = this.buildRenderBufferFuture.getNow(null);
				this.buildRenderBufferFuture = null;
				if (newBuffer == null) {
					// failed.
					markBufferDirty();
					return false;
				}
				LodUtil.assertTrue(newBuffer.buffersUploaded, "The buffer future for "+pos+" returned an un-built buffer.");
				ColumnRenderBuffer oldBuffer = this.activeRenderBufferRef.getAndSet(newBuffer);
				if (oldBuffer != null)
				{
					// the old buffer is now considered unloaded, it will need to be freshly re-loaded
					oldBuffer.buffersUploaded = false;
				}
				ColumnRenderBuffer swapped = this.inactiveRenderBufferRef.swap(oldBuffer);
				didSwapped = true;
				LodUtil.assertTrue(swapped == null);
			}
			catch (CancellationException e1) {
				// ignore.
				this.buildRenderBufferFuture = null;
			}
			catch (CompletionException e) {
				LOGGER.error("Unable to get render buffer for "+pos+".", e);
				this.buildRenderBufferFuture = null;
			}
		}
		return didSwapped;
	}

	//==============//
	// base methods //
	//==============//
	
    public String toString() {
        return "LodRenderSection{" +
                "pos=" + this.pos +
                ", lodRenderSource=" + this.renderSource +
                ", loadFuture=" + this.renderSourceLoadFuture +
                ", isRenderEnabled=" + this.isRenderingEnabled +
                '}';
    }

	public void dispose() {
		disposeRenderData();
		DebugRenderer.unregister(this);
		if (doDisposeActiveBuffer && this.activeRenderBufferRef.get() != null) {
			this.activeRenderBufferRef.get().close();
		}
	}

	public void disposeRenderData()
	{
		disposeRenderBuffer();
		this.renderSource = null;
		if (this.renderSourceLoadFuture != null)
		{
			this.renderSourceLoadFuture.cancel(true);
			this.renderSourceLoadFuture = null;
		}
	}

	public void disposeRenderBuffer()
	{
		cancelBuildBuffer();
		doDisposeActiveBuffer = true;
	}

	public void markBufferDirty() {
		lastSwapLocalVersion = -1;
	}
}
