package com.seibel.distanthorizons.core.pooling;

import com.seibel.distanthorizons.core.util.ListUtil;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;

import java.util.ArrayList;

/**
 * This keeps track of all the poolable
 * arrays that can be retrieved via the {@link PhantomArrayListPool}.
 * 
 * @see PhantomArrayListParent
 * @see PhantomArrayListPool
 */
public class PhantomArrayListCheckout implements AutoCloseable
{
	private final ArrayList<ByteArrayList> byteArrayLists = new ArrayList<>();
	private final ArrayList<ShortArrayList> shortArrayLists = new ArrayList<>();
	private final ArrayList<LongArrayList> longArrayLists = new ArrayList<>();
	
	
	
	//=========//
	// setters //
	//=========//
	
	public void addByteArrayList(ByteArrayList list) { this.byteArrayLists.add(list); }
	public void addShortArrayList(ShortArrayList list) { this.shortArrayLists.add(list); }
	public void addLongArrayList(LongArrayList list) { this.longArrayLists.add(list); }
	
	
	
	//=========//
	// getters //
	//=========//
	
	public int getByteArrayCount() { return this.byteArrayLists.size(); }
	public int getShortArrayCount() { return this.shortArrayLists.size(); }
	public int getLongArrayCount() { return this.longArrayLists.size(); }
	
	
	
	public ByteArrayList getByteArray(int index) { return this.getByteArray(index, 0); }
	public ByteArrayList getByteArray(int index, int size)
	{
		ByteArrayList list = this.byteArrayLists.get(index);
		if (size != 0)
		{
			ListUtil.clearAndSetSize(list, size);
		}
		else
		{
			list.clear();
		}
		return list;
	}
	
	public ShortArrayList getShortArray(int index) { return this.getShortArray(index, 0); }
	public ShortArrayList getShortArray(int index, int size)
	{
		ShortArrayList list = this.shortArrayLists.get(index);
		if (size != 0)
		{
			ListUtil.clearAndSetSize(list, size);
		}
		else
		{
			list.clear();
		}
		return list;
	}
	
	public LongArrayList getLongArray(int index) { return this.getLongArray(index, 0); }
	public LongArrayList getLongArray(int index, int size)
	{
		LongArrayList list = this.longArrayLists.get(index);
		if (size != 0)
		{
			ListUtil.clearAndSetSize(list, size);
		}
		else
		{
			list.clear();
		}
		return list;
	}
	
	
	public ArrayList<ByteArrayList> getAllByteArrays() { return this.byteArrayLists; }
	public ArrayList<ShortArrayList> getAllShortArrays() { return this.shortArrayLists; }
	public ArrayList<LongArrayList> getAllLongArrays() { return this.longArrayLists; }
	
	
	
	@Override 
	public void close()
	{
		PhantomArrayListPool.INSTANCE.returnCheckout(this);
	}
	
}
