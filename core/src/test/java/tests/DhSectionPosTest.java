/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2023 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package tests;

import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class DhSectionPosTest
{
	
	@Test
	public void basicEncodeDecodeTest()
	{
		long pos;
		
		// zero pos
		pos = DhSectionPos.encode((byte) 0, 0, 0);
		assertSectionPosEqual(0, DhSectionPos.getDetailLevel(pos));
		assertSectionPosEqual(0, DhSectionPos.getX(pos));
		assertSectionPosEqual(0, DhSectionPos.getZ(pos));
		
		// positive values
		pos = DhSectionPos.encode((byte) 10, 4, 1);
		assertSectionPosEqual(10, DhSectionPos.getDetailLevel(pos));
		assertSectionPosEqual(4, DhSectionPos.getX(pos));
		assertSectionPosEqual(1, DhSectionPos.getZ(pos));
		
		// negative position, positive detail level
		pos = DhSectionPos.encode((byte) 2, -1, -4);
		assertSectionPosEqual(2, DhSectionPos.getDetailLevel(pos));
		assertSectionPosEqual(-1, DhSectionPos.getX(pos));
		assertSectionPosEqual(-4, DhSectionPos.getZ(pos));
		
	}
	
	
	
	@Test
	public void containsPosTest()
	{
		long root = DhSectionPos.encode((byte) 10, 0, 0);
		long child = DhSectionPos.encode((byte) 9, 1, 1);
		
		Assert.assertTrue("section pos contains fail", DhSectionPos.contains(root, child));
		Assert.assertFalse("section pos contains fail", DhSectionPos.contains(child, root));
		
		
		root = DhSectionPos.encode((byte) 10, 1, 0);
		
		// out of bounds
		child = DhSectionPos.encode((byte) 9, 0, 0);
		Assert.assertFalse("position should be out of bounds", DhSectionPos.contains(root, child));
		child = DhSectionPos.encode((byte) 9, 1, 1);
		Assert.assertFalse("position should be out of bounds", DhSectionPos.contains(root, child));
		
		// in bounds
		child = DhSectionPos.encode((byte) 9, 2, 0);
		Assert.assertTrue("position should be in bounds", DhSectionPos.contains(root, child));
		child = DhSectionPos.encode((byte) 9, 3, 1);
		Assert.assertTrue("position should be in bounds", DhSectionPos.contains(root, child));
		
		// out of bounds
		child = DhSectionPos.encode((byte) 9, 2, 2);
		Assert.assertFalse("position should be out of bounds", DhSectionPos.contains(root, child));
		child = DhSectionPos.encode((byte) 9, 3, 3);
		Assert.assertFalse("position should be out of bounds", DhSectionPos.contains(root, child));
		
		child = DhSectionPos.encode((byte) 9, 4, 4);
		Assert.assertFalse("position should be out of bounds", DhSectionPos.contains(root, child));
		child = DhSectionPos.encode((byte) 9, 5, 5);
		Assert.assertFalse("position should be out of bounds", DhSectionPos.contains(root, child));
	}
	
	@Test
	public void containsAdjacentPosTest()
	{
		// neither should contain the other, they are single blocks that are next to each other
		long left = DhSectionPos.encode((byte) 0, 4606, 0);
		long right = DhSectionPos.encode((byte) 0, 4607, 0);
		Assert.assertFalse(DhSectionPos.contains(left, right));
		Assert.assertFalse(DhSectionPos.contains(right, left));


		// 512 block wide sections that are adjacent, but not overlapping
		left = DhSectionPos.encode((byte) 9, 0, 0);
		right = DhSectionPos.encode((byte) 9, 1, 0);
		Assert.assertFalse(DhSectionPos.contains(left, right));
		Assert.assertFalse(DhSectionPos.contains(right, left));

	}
	
	@Test
	public void parentPosTest()
	{
		long leaf = DhSectionPos.encode((byte) 0, 0, 0);
		long convert = DhSectionPos.convertToDetailLevel((byte) 1, leaf);
		long parent = DhSectionPos.getParentPos(leaf);
		assertSectionPosEqual("get parent at 0,0 fail", convert, parent);


		leaf = DhSectionPos.encode((byte) 0, 1, 1);
		convert = DhSectionPos.convertToDetailLevel((byte) 1, leaf);
		parent = DhSectionPos.getParentPos(leaf);
		assertSectionPosEqual("get parent at 1,1 fail", convert, parent);


		leaf = DhSectionPos.encode((byte) 1, 2, 2);
		convert = DhSectionPos.convertToDetailLevel((byte) 2, leaf);
		parent = DhSectionPos.getParentPos(leaf);
		assertSectionPosEqual("parent upscale fail", convert, parent);
		convert = DhSectionPos.convertToDetailLevel((byte) 0, leaf);
		long childIndex = DhSectionPos.getChildByIndex(0, leaf);
		assertSectionPosEqual("child detail fail", convert, childIndex);

	}
	
	@Test
	public void childPosTest()
	{
		long node = DhSectionPos.encode((byte) 1, 2302, 0);
		long nw = DhSectionPos.getChildByIndex(0, node);
		long sw = DhSectionPos.getChildByIndex(1, node);
		long ne = DhSectionPos.getChildByIndex(2, node);
		long se = DhSectionPos.getChildByIndex(3, node);
		
		// confirm no children have the same values
		Assert.assertNotEquals(nw, sw);
		Assert.assertNotEquals(sw, ne);
		Assert.assertNotEquals(ne, se);
		
		// confirm each child has the correct value
		assertSectionPosEqual(nw, DhSectionPos.encode((byte) 0, 4604, 0));
		assertSectionPosEqual(sw, DhSectionPos.encode((byte) 0, 4605, 0));
		assertSectionPosEqual(ne, DhSectionPos.encode((byte) 0, 4604, 1));
		assertSectionPosEqual(se, DhSectionPos.encode((byte) 0, 4605, 1));
		
	}
	
	@Test
	public void getCenterBlockTest()
	{
		long node = DhSectionPos.encode((byte) 1, 2303, 0);
		DhBlockPos2D centerBlockPos = DhSectionPos.getCenterBlockPos(node);
		DhBlockPos2D expectedCenterNode = new DhBlockPos2D(4606, 0);
		Assert.assertEquals(expectedCenterNode, centerBlockPos);



		node = DhSectionPos.encode((byte) 10, 0, 0); // 1024 blocks wide
		centerBlockPos = DhSectionPos.getCenterBlockPos(node);
		expectedCenterNode = new DhBlockPos2D(1024 / 2, 1024 / 2);
		Assert.assertEquals(expectedCenterNode, centerBlockPos);

	}
	
	@Test
	public void getCenterBlock2DTest()
	{
		long parentNode = DhSectionPos.encode((byte) 2, 1151, 0); // width 4 blocks
		long inputPos = DhSectionPos.encode((byte) 0, 4606, 0); // width 1 block
		Assert.assertTrue(DhSectionPos.contains(parentNode, inputPos));

		DhBlockPos2D parentCenter = DhSectionPos.getCenterBlockPos(parentNode);
		DhBlockPos2D inputCenter = DhSectionPos.getCenterBlockPos(inputPos);
		
		Assert.assertEquals(new DhBlockPos2D(4606, 2), parentCenter);
		Assert.assertEquals(new DhBlockPos2D(4606, 0), inputCenter);

	}
	
	@Test
	public void createFromBlockPos()
	{
		// origin pos //
		
		DhBlockPos originBlockPos = new DhBlockPos(0, 0, 0);
		long originsectionPos = DhSectionPos.encode(originBlockPos);
		assertSectionPosEqual(DhSectionPos.encode(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, 0, 0), originsectionPos);
		
		
		// offset pos //
		long offsetSectionPos;
		
		DhBlockPos offsetBlockPos = new DhBlockPos(1000, 0, 42000);
		offsetSectionPos = DhSectionPos.encode(offsetBlockPos);
		assertSectionPosEqual(DhSectionPos.encode(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, 15, 656), offsetSectionPos);
		
		offsetBlockPos = new DhBlockPos(-987654, 0, 46);
		offsetSectionPos = DhSectionPos.encode(offsetBlockPos);
		assertSectionPosEqual(DhSectionPos.encode(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, -15433, 0), offsetSectionPos);
		
	}
	
	@Test
	public void createFromBlockPos2D()
	{
		// origin pos //
		
		DhBlockPos2D originBlockPos = new DhBlockPos2D(0, 0);
		long originSectionPos = DhSectionPos.encode(originBlockPos);
		assertSectionPosEqual(DhSectionPos.encode(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, 0, 0), originSectionPos);
		
		
		// offset pos //
		
		DhBlockPos2D offsetBlockPos = new DhBlockPos2D(1000, 42000);
		long offsetSectionPos = DhSectionPos.encode(offsetBlockPos);
		assertSectionPosEqual(DhSectionPos.encode(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, 15, 656), offsetSectionPos);
		
		offsetBlockPos = new DhBlockPos2D(-987654, 46);
		offsetSectionPos = DhSectionPos.encode(offsetBlockPos);
		assertSectionPosEqual(DhSectionPos.encode(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, -15433, 0), offsetSectionPos);
		
	}
	
	@Test
	public void createFromChunkPos()
	{
		// origin pos //
		
		DhChunkPos originChunkPos = new DhChunkPos(0,0);
		long originSectionPos = DhSectionPos.encode(originChunkPos);
		assertSectionPosEqual(DhSectionPos.encode(DhSectionPos.SECTION_CHUNK_DETAIL_LEVEL, 0, 0), originSectionPos);
		
		
		// offset pos //
		
		DhChunkPos offsetChunkPos = new DhChunkPos(1000, 42000);
		long offsetSectionPos = DhSectionPos.encode(offsetChunkPos);
		assertSectionPosEqual(DhSectionPos.encode(DhSectionPos.SECTION_CHUNK_DETAIL_LEVEL, 15, 656), offsetSectionPos);
		
		offsetChunkPos = new DhChunkPos(-987654, 46);
		offsetSectionPos = DhSectionPos.encode(offsetChunkPos);
		assertSectionPosEqual(DhSectionPos.encode(DhSectionPos.SECTION_CHUNK_DETAIL_LEVEL, -15433, 0), offsetSectionPos);
		
	}
	
	@Test
	public void convertToDetailLevel()
	{
		// origin pos //
		
		long originSectionPos = DhSectionPos.encode((byte) 0,0,0);
		
		originSectionPos = DhSectionPos.convertToDetailLevel((byte) 1, originSectionPos);
		assertSectionPosEqual(DhSectionPos.encode((byte) 1, 0, 0), originSectionPos);
		
		originSectionPos = DhSectionPos.convertToDetailLevel(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, originSectionPos);
		assertSectionPosEqual(DhSectionPos.encode(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, 0, 0), originSectionPos);
		
		originSectionPos = DhSectionPos.convertToDetailLevel(DhSectionPos.SECTION_REGION_DETAIL_LEVEL, originSectionPos);
		assertSectionPosEqual(DhSectionPos.encode(DhSectionPos.SECTION_REGION_DETAIL_LEVEL, 0, 0), originSectionPos);
		
		
		// offset pos //
		
		long offsetSectionPos = DhSectionPos.encode((byte) 0,-10000,5000);
		
		offsetSectionPos = DhSectionPos.convertToDetailLevel((byte) 1, offsetSectionPos);
		assertSectionPosEqual(DhSectionPos.encode((byte) 1, -5000, 2500), offsetSectionPos);
		
		offsetSectionPos = DhSectionPos.convertToDetailLevel(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, offsetSectionPos);
		assertSectionPosEqual(DhSectionPos.encode(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, -157, 78), offsetSectionPos);
		
		offsetSectionPos = DhSectionPos.convertToDetailLevel(DhSectionPos.SECTION_REGION_DETAIL_LEVEL, offsetSectionPos);
		assertSectionPosEqual(DhSectionPos.encode(DhSectionPos.SECTION_REGION_DETAIL_LEVEL, -1, 0), offsetSectionPos);
		
	}
	
	@Test
	public void getOffsetWidth()
	{
		long originSectionPos = DhSectionPos.encode((byte) 0,0,0);
		long sectionPos = DhSectionPos.encode((byte) 0,-10000,5000);
		
		
		
		// 1 -> 0
		byte returnDetailLevel = 0;
		originSectionPos = DhSectionPos.convertToDetailLevel((byte) 1, originSectionPos);
		assertSectionPosEqual(2, DhSectionPos.getWidthCountForLowerDetailedSection(returnDetailLevel, originSectionPos));
		
		sectionPos = DhSectionPos.convertToDetailLevel((byte) 1, sectionPos);
		assertSectionPosEqual(2, DhSectionPos.getWidthCountForLowerDetailedSection(returnDetailLevel, sectionPos));
		
		
		// 2 -> 1
		returnDetailLevel = 1;
		originSectionPos = DhSectionPos.convertToDetailLevel((byte) 2, originSectionPos);
		assertSectionPosEqual(2, DhSectionPos.getWidthCountForLowerDetailedSection(returnDetailLevel, originSectionPos));
		
		sectionPos = DhSectionPos.convertToDetailLevel((byte) 2, sectionPos);
		assertSectionPosEqual(2, DhSectionPos.getWidthCountForLowerDetailedSection(returnDetailLevel, sectionPos));
		
		
		// Block -> 0
		returnDetailLevel = 0;
		originSectionPos = DhSectionPos.convertToDetailLevel(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, originSectionPos);
		assertSectionPosEqual(64, DhSectionPos.getWidthCountForLowerDetailedSection(returnDetailLevel, originSectionPos));
		
		sectionPos = DhSectionPos.convertToDetailLevel(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, sectionPos);
		assertSectionPosEqual(64, DhSectionPos.getWidthCountForLowerDetailedSection(returnDetailLevel, sectionPos));
		
		
		// Region -> 3
		returnDetailLevel = 3;
		originSectionPos = DhSectionPos.convertToDetailLevel(DhSectionPos.SECTION_REGION_DETAIL_LEVEL, originSectionPos);
		assertSectionPosEqual(4096, DhSectionPos.getWidthCountForLowerDetailedSection(returnDetailLevel, originSectionPos));
		
		sectionPos = DhSectionPos.convertToDetailLevel(DhSectionPos.SECTION_REGION_DETAIL_LEVEL, sectionPos);
		assertSectionPosEqual(4096, DhSectionPos.getWidthCountForLowerDetailedSection(returnDetailLevel, sectionPos));
		
	}
	
	@Test
	public void getBlockWidth()
	{
		long originSectionPos = DhSectionPos.encode((byte) 0,0,0);
		long sectionPos = DhSectionPos.encode((byte) 0,-10000,5000);
		
		
		assertSectionPosEqual(1, DhSectionPos.getBlockWidth(originSectionPos));
		assertSectionPosEqual(1, DhSectionPos.getBlockWidth(sectionPos));
		
		originSectionPos = DhSectionPos.convertToDetailLevel((byte) 1, originSectionPos);
		assertSectionPosEqual(2, DhSectionPos.getBlockWidth(originSectionPos));
		sectionPos = DhSectionPos.convertToDetailLevel((byte) 1, sectionPos);
		assertSectionPosEqual(2, DhSectionPos.getBlockWidth(sectionPos));
		
		originSectionPos = DhSectionPos.convertToDetailLevel(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, originSectionPos);
		assertSectionPosEqual(64, DhSectionPos.getBlockWidth(originSectionPos));
		sectionPos = DhSectionPos.convertToDetailLevel(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, sectionPos);
		assertSectionPosEqual(64, DhSectionPos.getBlockWidth(sectionPos));
		
		originSectionPos = DhSectionPos.convertToDetailLevel(DhSectionPos.SECTION_REGION_DETAIL_LEVEL, originSectionPos);
		assertSectionPosEqual(32768, DhSectionPos.getBlockWidth(originSectionPos));
		sectionPos = DhSectionPos.convertToDetailLevel(DhSectionPos.SECTION_REGION_DETAIL_LEVEL, sectionPos);
		assertSectionPosEqual(32768, DhSectionPos.getBlockWidth(sectionPos));
		
	}
	
	@Test
	public void getCenterBlockPos()
	{
		long originSectionPos = DhSectionPos.encode((byte) 0,0,0);
		long sectionPos = DhSectionPos.encode((byte) 0,-10000,5000);
		
		
		Assert.assertEquals(new DhBlockPos2D(0, 0), DhSectionPos.getCenterBlockPos(originSectionPos));
		Assert.assertEquals(new DhBlockPos2D(-10000, 5000), DhSectionPos.getCenterBlockPos(sectionPos));
		
		
		originSectionPos = DhSectionPos.convertToDetailLevel((byte) 1, originSectionPos);
		Assert.assertEquals(new DhBlockPos2D(0, 0), DhSectionPos.getCenterBlockPos(originSectionPos));
		sectionPos = DhSectionPos.convertToDetailLevel((byte) 1, sectionPos);
		Assert.assertEquals(new DhBlockPos2D(-10000, 5000), DhSectionPos.getCenterBlockPos(sectionPos));
		
		
		originSectionPos = DhSectionPos.convertToDetailLevel(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, originSectionPos);
		Assert.assertEquals(new DhBlockPos2D(32, 32), DhSectionPos.getCenterBlockPos(originSectionPos));
		sectionPos = DhSectionPos.convertToDetailLevel(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, sectionPos);
		Assert.assertEquals(new DhBlockPos2D(-10016, 5024), DhSectionPos.getCenterBlockPos(sectionPos));


		originSectionPos = DhSectionPos.convertToDetailLevel(DhSectionPos.SECTION_REGION_DETAIL_LEVEL, originSectionPos);
		Assert.assertEquals(new DhBlockPos2D(16384, 16384), DhSectionPos.getCenterBlockPos(originSectionPos));
		sectionPos = DhSectionPos.convertToDetailLevel(DhSectionPos.SECTION_REGION_DETAIL_LEVEL, sectionPos);
		Assert.assertEquals(new DhBlockPos2D(-16384, 16384), DhSectionPos.getCenterBlockPos(sectionPos));

	}
	
	@Test
	public void getMinCornerBlockPos()
	{
		long pos;
		
		// origin block detail
		pos = DhSectionPos.encode((byte) 0,0,0);
		Assert.assertEquals(0, DhSectionPos.getMinCornerBlockX(pos));
		Assert.assertEquals(0, DhSectionPos.getMinCornerBlockZ(pos));
		
		// offset block detail
		pos = DhSectionPos.encode((byte) 0,2,3);
		Assert.assertEquals(2 * DhSectionPos.getBlockWidth(pos), DhSectionPos.getMinCornerBlockX(pos));
		Assert.assertEquals(3 * DhSectionPos.getBlockWidth(pos), DhSectionPos.getMinCornerBlockZ(pos));
		
		// negative offset block detail
		pos = DhSectionPos.encode((byte) 0,-1,-2);
		Assert.assertEquals(-1 * DhSectionPos.getBlockWidth(pos), DhSectionPos.getMinCornerBlockX(pos));
		Assert.assertEquals(-2 * DhSectionPos.getBlockWidth(pos), DhSectionPos.getMinCornerBlockZ(pos));
		
		
		// origin chunk detail
		pos = DhSectionPos.encode(DhSectionPos.SECTION_CHUNK_DETAIL_LEVEL,0,0);
		Assert.assertEquals(0, DhSectionPos.getMinCornerBlockX(pos));
		Assert.assertEquals(0, DhSectionPos.getMinCornerBlockZ(pos));
		
		// offset chunk detail
		pos = DhSectionPos.encode(DhSectionPos.SECTION_CHUNK_DETAIL_LEVEL,2,3);
		Assert.assertEquals(2 * DhSectionPos.getBlockWidth(pos), DhSectionPos.getMinCornerBlockX(pos));
		Assert.assertEquals(3 * DhSectionPos.getBlockWidth(pos), DhSectionPos.getMinCornerBlockZ(pos));
		
		
	}
	
	@Test
	public void getAdjacentPos()
	{
		long pos = DhSectionPos.encode((byte) 0, 0, 0);
		
		assertSectionPosEqual(DhSectionPos.encode((byte) 0, 0, -1), DhSectionPos.getAdjacentPos(EDhDirection.NORTH, pos));
		assertSectionPosEqual(DhSectionPos.encode((byte) 0, 0, 1), DhSectionPos.getAdjacentPos(EDhDirection.SOUTH, pos));
		
		assertSectionPosEqual(DhSectionPos.encode((byte) 0, 1, 0), DhSectionPos.getAdjacentPos(EDhDirection.EAST, pos));
		assertSectionPosEqual(DhSectionPos.encode((byte) 0, -1, 0), DhSectionPos.getAdjacentPos(EDhDirection.WEST, pos));
		
		// getting the adjacent position in the up and down position don't make sense
		Assert.assertThrows(IllegalArgumentException.class, () -> { DhSectionPos.getAdjacentPos(EDhDirection.UP, pos); });
		Assert.assertThrows(IllegalArgumentException.class, () -> { DhSectionPos.getAdjacentPos(EDhDirection.DOWN, pos); });
	}
	
	@Test
	public void forEachChildIterator()
	{
		long pos = DhSectionPos.encode((byte) 1, 0, 0);
		
		ArrayList<Long> childPosList = new ArrayList<>();
		AtomicInteger childCount = new AtomicInteger(0);
		DhSectionPos.forEachChild((childPos) -> 
		{
			childCount.incrementAndGet();
			childPosList.add(childPos);
		}, pos);
		
		Assert.assertTrue(childPosList.contains(DhSectionPos.encode((byte) 0, 0, 0)));
		Assert.assertTrue(childPosList.contains(DhSectionPos.encode((byte) 0, 1, 0)));
		Assert.assertTrue(childPosList.contains(DhSectionPos.encode((byte) 0, 0, 1)));
		Assert.assertTrue(childPosList.contains(DhSectionPos.encode((byte) 0, 1, 1)));
		
	}
	
	
	
	
	//================//
	// helper methods //
	//================//
	
	public static void assertSectionPosEqual(long expected, long actual) { assertSectionPosEqual("", expected, actual); }
	public static void assertSectionPosEqual(String messagePrefix, long expected, long actual)
	{
		if (!messagePrefix.endsWith(" "))
		{
			messagePrefix += " ";
		}
		
		String expectedString = DhSectionPos.toString(expected);
		String actualString = DhSectionPos.toString(actual);
		String mismatchSuffix = "expected: ["+expectedString+"] actual: ["+actualString+"].";
		
		Assert.assertEquals(messagePrefix+"Detail level mismatch, "+mismatchSuffix, DhSectionPos.getDetailLevel(expected), DhSectionPos.getDetailLevel(actual));
		Assert.assertEquals(messagePrefix+"X Pos mismatch, "+mismatchSuffix, DhSectionPos.getX(expected), DhSectionPos.getX(actual));
		Assert.assertEquals(messagePrefix+"Z Pos mismatch, "+mismatchSuffix, DhSectionPos.getZ(expected), DhSectionPos.getZ(actual));
	}
	
	
	
}
