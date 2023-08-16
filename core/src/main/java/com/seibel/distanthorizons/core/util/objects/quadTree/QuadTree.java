package com.seibel.distanthorizons.core.util.objects.quadTree;

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.Pos2D;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import com.seibel.distanthorizons.coreapi.util.MathUtil;
import com.seibel.distanthorizons.core.util.gridList.MovableGridRingList;
import org.apache.logging.log4j.Logger;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * This class represents a quadTree of T type values.
 */
public class QuadTree<T>
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	
	
	/** The largest number detail level in this tree. */
	public final byte treeMaxDetailLevel;
	/** The smallest number detail level in this tree. */
	public final byte treeMinDetailLevel;
	
	/** contain the actual data in the quad tree structure */
	private final MovableGridRingList<QuadNode<T>> topRingList;
	
	private DhBlockPos2D centerBlockPos;
	
	private int widthInBlocks;
	
	
	
	/**
	 * Constructor of the quadTree
	 *
	 * @param widthInBlocks equivalent to the distance between two opposing sides
	 */
	public QuadTree(int widthInBlocks, DhBlockPos2D centerBlockPos, byte treeMinDetailLevel)
	{
		this.centerBlockPos = centerBlockPos;
		this.widthInBlocks = widthInBlocks;
		
		this.treeMinDetailLevel = treeMinDetailLevel;
		// the max detail level must be greater than 0 (to prevent divide by 0 errors) and greater than the minimum detail level
		this.treeMaxDetailLevel = (byte) Math.max(Math.max(1, this.treeMinDetailLevel), MathUtil.log2(widthInBlocks));
		
		int halfSizeInRootNodes = Math.floorDiv(this.widthInBlocks, 2) / BitShiftUtil.powerOfTwo(this.treeMaxDetailLevel);
		halfSizeInRootNodes = halfSizeInRootNodes + 1; // always add 1 so nodes will always have a parent, even if the tree's center is offset from the root node grid 
		
		Pos2D ringListCenterPos = new Pos2D(
				BitShiftUtil.divideByPowerOfTwo(this.centerBlockPos.x, this.treeMaxDetailLevel),
				BitShiftUtil.divideByPowerOfTwo(this.centerBlockPos.z, this.treeMaxDetailLevel));
		this.topRingList = new MovableGridRingList<>(halfSizeInRootNodes, ringListCenterPos.x, ringListCenterPos.y);
		
	}
	
	
	
	//=====================//
	// getters and setters //
	//=====================//
	
	/** @return the node at the given section position */
	public final QuadNode<T> getNode(DhSectionPos pos) throws IndexOutOfBoundsException { return this.getOrSetNode(pos, false, null, true); }
	/** @return the value at the given section position */
	public final T getValue(DhSectionPos pos) throws IndexOutOfBoundsException
	{
		QuadNode<T> node = this.getNode(pos);
		if (node != null)
		{
			return node.value;
		}
		return null;
	}
	
	/** @return the value that was previously in the given position, null if nothing */
	public final T setValue(DhSectionPos pos, T value) throws IndexOutOfBoundsException
	{
		T previousValue = this.getValue(pos);
		this.getOrSetNode(pos, true, value, true);
		return previousValue;
	}
	
	/** @param runBoundaryChecks should only ever be set to true internally for removing out of bound nodes */
	protected final QuadNode<T> getOrSetNode(DhSectionPos pos, boolean setNewValue, T newValue, boolean runBoundaryChecks) throws IndexOutOfBoundsException
	{
		if (!runBoundaryChecks || this.isSectionPosInBounds(pos))
		{
			DhSectionPos rootPos = pos.convertToDetailLevel(this.treeMaxDetailLevel);
			int ringListPosX = rootPos.sectionX;
			int ringListPosZ = rootPos.sectionZ;
			
			QuadNode<T> topQuadNode = this.topRingList.get(ringListPosX, ringListPosZ);
			if (topQuadNode == null)
			{
				if (!setNewValue)
				{
					return null;
				}
				
				topQuadNode = new QuadNode<T>(rootPos, this.treeMinDetailLevel);
				boolean successfullyAdded = this.topRingList.set(ringListPosX, ringListPosZ, topQuadNode);
				LodUtil.assertTrue(successfullyAdded, "Failed to add top quadTree node at position: " + rootPos);
			}
			
			if (!topQuadNode.sectionPos.contains(pos))
			{
				LodUtil.assertNotReach("failed to get a root node that contains the input position: " + pos + " root node pos: " + topQuadNode.sectionPos);
			}
			
			
			QuadNode<T> returnNode = topQuadNode.getNode(pos);
			if (setNewValue)
			{
				topQuadNode.setValue(pos, newValue);
			}
			return returnNode;
		}
		else
		{
			int radius = this.diameterInBlocks() / 2;
			DhBlockPos2D minPos = this.getCenterBlockPos().add(new DhBlockPos2D(-radius, -radius));
			DhBlockPos2D maxPos = this.getCenterBlockPos().add(new DhBlockPos2D(radius, radius));
			throw new IndexOutOfBoundsException("QuadTree GetOrSet failed. Position out of bounds, min pos: " + minPos + ", max pos: " + maxPos + ", min detail level: " + this.treeMinDetailLevel + ", max detail level: " + this.treeMaxDetailLevel + ". Given Position: " + pos + " = block pos: " + pos.convertToDetailLevel(LodUtil.BLOCK_DETAIL_LEVEL));
		}
	}
	
	public boolean isSectionPosInBounds(DhSectionPos testPos)
	{
		// check if the testPos is within the detail level limits of the tree
		boolean detailLevelWithinBounds = this.treeMinDetailLevel <= testPos.sectionDetailLevel && testPos.sectionDetailLevel <= this.treeMaxDetailLevel;
		if (!detailLevelWithinBounds)
		{
			return false;
		}
		
		
		// check if the testPos is within the X,Z boundary of the tree
		DhBlockPos2D treeBlockCorner = this.centerBlockPos.add(new DhBlockPos2D(-this.widthInBlocks / 2, -this.widthInBlocks / 2));
		DhLodPos treeCornerPos = new DhLodPos((byte) 0, treeBlockCorner.x, treeBlockCorner.z);
		
		DhSectionPos inputSectionCorner = testPos.convertToDetailLevel((byte) 0);
		DhLodPos inputCornerPos = new DhLodPos((byte) 0, inputSectionCorner.sectionX, inputSectionCorner.sectionZ);
		int inputBlockWidth = BitShiftUtil.powerOfTwo(testPos.sectionDetailLevel);
		
		return DoSquaresOverlap(treeCornerPos, this.widthInBlocks, inputCornerPos, inputBlockWidth);
	}
	private static boolean DoSquaresOverlap(DhLodPos square1Min, int square1Width, DhLodPos square2Min, int square2Width)
	{
		// Determine the coordinates of the squares (the variables say rect[angle] because this logic would also work there and was simplified to work for squares)
		float rect1MinX = square1Min.x;
		float rect1MaxX = square1Min.x + square1Width;
		float rect1MinZ = square1Min.z;
		float rect1MaxZ = square1Min.z + square1Width;
		
		float rect2MinX = square2Min.x;
		float rect2MaxX = square2Min.x + square2Width;
		float rect2MinZ = square2Min.z;
		float rect2MaxZ = square2Min.z + square2Width;
		
		// Check if the squares overlap
		return
				rect1MinX < rect2MaxX &&
						rect1MaxX > rect2MinX &&
						rect1MinZ < rect2MaxZ &&
						rect1MaxZ > rect2MinZ;
	}
	
	
	public int getNonNullChildCountAtPos(DhSectionPos pos) { return this.getChildCountAtPos(pos, false); }
	public int getChildCountAtPos(DhSectionPos pos, boolean includeNullValues)
	{
		int childCount = 0;
		for (int i = 0; i < 4; i++)
		{
			DhSectionPos childPos = pos.getChildByIndex(i);
			if (this.isSectionPosInBounds(childPos))
			{
				T value = this.getValue(childPos);
				if (includeNullValues || value != null)
				{
					childCount++;
				}
			}
		}
		
		return childCount;
	}
	
	
	
	//===========//
	// iterators //
	//===========//
	
	/** can include null nodes */
	public Iterator<DhSectionPos> rootNodePosIterator() { return new QuadTreeRootPosIterator(true); }
	
	public Iterator<QuadNode<T>> nodeIterator() { return new QuadTreeNodeIterator(false); }
	public Iterator<QuadNode<T>> leafNodeIterator() { return new QuadTreeNodeIterator(true); }
	
	
	
	//================//
	// get/set center //
	//================//
	
	public void setCenterBlockPos(DhBlockPos2D newCenterPos) { this.setCenterBlockPos(newCenterPos, null); }
	public void setCenterBlockPos(DhBlockPos2D newCenterPos, Consumer<? super T> removedItemConsumer)
	{
		this.centerBlockPos = newCenterPos;
		
		Pos2D expectedCenterPos = new Pos2D(
				BitShiftUtil.divideByPowerOfTwo(this.centerBlockPos.x, this.treeMaxDetailLevel),
				BitShiftUtil.divideByPowerOfTwo(this.centerBlockPos.z, this.treeMaxDetailLevel));
		
		if (this.topRingList.getCenter().equals(expectedCenterPos))
		{
			// tree doesn't need to be moved
			return;
		}
		
		
		// remove out of bounds root nodes
		this.topRingList.moveTo(expectedCenterPos.x, expectedCenterPos.y, (quadNode) ->
		{
			if (quadNode != null && removedItemConsumer != null)
			{
				removedItemConsumer.accept(quadNode.value);
			}
		});


//		// remove out of bound nodes and clean up empty nodes
//		// Note: this will iterate over a lot of unnecessary nodes, hopefully speed won't be an issue
//		Iterator<DhSectionPos> rootNodePosIterator = this.rootNodePosIterator();
//		while (rootNodePosIterator.hasNext())
//		{
//			// get the root node (regular nodeIterators won't return them if they are out of bounds)
//			DhSectionPos rootPos = rootNodePosIterator.next();
//			QuadNode<T> rootNode = this.getOrSetNode(rootPos, false, null, false);
//			if (rootNode == null)
//			{
//				continue;
//			}
//			
//			// remove any child nodes that are out of bounds
//			Iterator<QuadNode<T>> nodeIterator = this.nodeIterator();
//			while (nodeIterator.hasNext())
//			{
//				QuadNode<T> node = nodeIterator.next();
//				if(!this.isSectionPosInBounds(node.sectionPos))
//				{
//					// node is out of bounds
//					
//					// FIXME(?) this appears to potentially return large nodes that are partially or entirely in bounds
//					
//					if (node.getNonNullChildCount() == 0)
//					{
//						// no child nodes, can be safely removed
//						nodeIterator.remove();
//					}
//					else
//					{
//						// node can't be removed, but its value can be set to null
//						node.value = null;
//					}
//				}
//			}
//		}
		
	}
	
	public final DhBlockPos2D getCenterBlockPos() { return this.centerBlockPos; }
	
	
	
	
	
	//==============//
	// base methods //
	//==============//
	
	public boolean isEmpty() { return this.count() == 0; } // TODO this should be rewritten to short-circuit
	
	/** @return the number of non-null nodes in the tree */
	public int count()
	{
		int count = 0;
		for (QuadNode<T> node : this.topRingList)
		{
			if (node == null)
			{
				continue;
			}
			
			Iterator<QuadNode<T>> nodeIterator = node.getNodeIterator();
			while (nodeIterator.hasNext())
			{
				if (nodeIterator.next().value != null)
				{
					count++;
				}
			}
		}
		
		return count;
	}
	
	/** @return the number of leaf nodes in the tree */
	public int leafNodeCount()
	{
		int count = 0;
		for (QuadNode<T> node : this.topRingList)
		{
			if (node == null)
			{
				continue;
			}
			
			Iterator<QuadNode<T>> leafNodeIterator = node.getLeafNodeIterator();
			while (leafNodeIterator.hasNext())
			{
				leafNodeIterator.next();
				count++;
			}
		}
		
		return count;
	}
	
	
	// TODO comment, currently a tree will always have 9 root nodes, because the tree will grow all the way up to the top, if this is ever changed then these values must also change 
	public int ringListWidth() { return 3; }
	public int ringListHalfWidth() { return 1; }
	public int diameterInBlocks() { return this.widthInBlocks; }

//	public String getDebugString()
//	{
//		StringBuilder sb = new StringBuilder();
//		for (byte i = 0; i < this.ringLists.length; i++)
//		{
//			sb.append("Layer ").append(i + TREE_LOWEST_DETAIL_LEVEL).append(":\n");
//			sb.append(this.ringLists[i].toDetailString());
//			sb.append("\n");
//			sb.append("\n");
//		}
//		return sb.toString();
//	}
	
	@Override
	public String toString() { return "center block: " + this.centerBlockPos + ", block width: " + this.widthInBlocks + ", detail level range: [" + this.treeMinDetailLevel + "-" + this.treeMaxDetailLevel + "], leaf #: " + this.leafNodeCount(); }
	
	
	
	//==================//
	// iterator classes //
	//==================//
	
	private class QuadTreeRootPosIterator implements Iterator<DhSectionPos>
	{
		private final Queue<DhSectionPos> iteratorPosQueue = new LinkedList<>();
		
		
		
		public QuadTreeRootPosIterator(boolean includeNullNodes)
		{
			QuadTree.this.topRingList.forEachPosOrdered((node, pos2D) ->
			{
				if (node != null || includeNullNodes)
				{
					DhSectionPos rootPos = new DhSectionPos(QuadTree.this.treeMaxDetailLevel, pos2D.x, pos2D.y);
					if (QuadTree.this.isSectionPosInBounds(rootPos))
					{
						this.iteratorPosQueue.add(rootPos);
					}
				}
			});
		}// constructor
		
		
		
		@Override
		public boolean hasNext() { return this.iteratorPosQueue.size() != 0; }
		
		@Override
		public DhSectionPos next()
		{
			if (this.iteratorPosQueue.size() == 0)
			{
				throw new NoSuchElementException();
			}
			
			
			DhSectionPos sectionPos = this.iteratorPosQueue.poll();
			return sectionPos;
		}
		
		
		/** Unimplemented */
		@Override
		public void remove() { throw new UnsupportedOperationException("remove"); }
		
		@Override
		public void forEachRemaining(Consumer<? super DhSectionPos> action) { Iterator.super.forEachRemaining(action); }
		
	}
	
	private class QuadTreeNodeIterator implements Iterator<QuadNode<T>>
	{
		private final QuadTreeRootPosIterator rootNodeIterator;
		private Iterator<QuadNode<T>> currentNodeIterator;
		
		private QuadNode<T> lastNode = null;
		
		private final boolean onlyReturnLeaves;
		
		
		
		public QuadTreeNodeIterator(boolean onlyReturnLeaves)
		{
			this.rootNodeIterator = new QuadTreeRootPosIterator(false);
			this.onlyReturnLeaves = onlyReturnLeaves;
		}
		
		
		
		@Override
		public boolean hasNext()
		{
			if (!this.rootNodeIterator.hasNext() && this.currentNodeIterator != null && !this.currentNodeIterator.hasNext())
			{
				return false;
			}
			
			
			if (this.currentNodeIterator == null || !this.currentNodeIterator.hasNext())
			{
				this.currentNodeIterator = this.getNextChildNodeIterator();
			}
			return this.currentNodeIterator != null && this.currentNodeIterator.hasNext();
		}
		
		@Override
		public QuadNode<T> next()
		{
			if (this.currentNodeIterator == null || !this.currentNodeIterator.hasNext())
			{
				this.currentNodeIterator = this.getNextChildNodeIterator();
			}
			
			
			this.lastNode = this.currentNodeIterator.next();
			return this.lastNode;
		}
		
		/** @return null if no new iterator could be found */
		private Iterator<QuadNode<T>> getNextChildNodeIterator()
		{
			Iterator<QuadNode<T>> nodeIterator = null;
			while ((nodeIterator == null || !nodeIterator.hasNext()) && this.rootNodeIterator.hasNext())
			{
				DhSectionPos sectionPos = this.rootNodeIterator.next();
				QuadNode<T> rootNode = QuadTree.this.getNode(sectionPos);
				if (rootNode != null)
				{
					nodeIterator = this.onlyReturnLeaves ? rootNode.getLeafNodeIterator() : rootNode.getNodeIterator();
				}
			}
			return nodeIterator;
		}
		
		
		@Override
		public void remove()
		{
			if (this.lastNode == null)
			{
				throw new NoSuchElementException("No last node found.");
			}
			
			
			QuadNode<T> node = QuadTree.this.getOrSetNode(this.lastNode.sectionPos, true, null, false);
			if (node != null)
			{
				node.deleteAllChildren();
			}
		}
		
		@Override
		public void forEachRemaining(Consumer<? super QuadNode<T>> action) { Iterator.super.forEachRemaining(action); }
		
	}
	
	
}
