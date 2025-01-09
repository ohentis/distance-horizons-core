package com.seibel.distanthorizons.core.pooling;

import com.seibel.distanthorizons.core.util.ListUtil;
import com.seibel.distanthorizons.coreapi.util.StringUtil;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.SoftReference;
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
	/** defines which pool the arrays should be returned too */
	private final PhantomArrayListPool owningPool;
	/** Will be null if the parent pool doesn't want leak stack tracing */
	@Nullable
	public final String allocationStackTrace;
	
	private final ArrayList<ByteArrayList> byteArrayLists = new ArrayList<>();
	private final ArrayList<ShortArrayList> shortArrayLists = new ArrayList<>();
	private final ArrayList<LongArrayList> longArrayLists = new ArrayList<>();
	private final ArrayList<SoftReference<LongArrayList>> longArrayRefLists = new ArrayList<>();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public PhantomArrayListCheckout(PhantomArrayListPool owningPool)
	{
		if (owningPool.logGarbageCollectedStacks)
		{
			// TODO remove the top 4 or so lines since those will always be the same (relating to the phantom allocations)
			//  and aren't helpful when debugging
			this.allocationStackTrace = StringUtil.join("\n", Thread.currentThread().getStackTrace());
		}
		else
		{
			this.allocationStackTrace = null;
		}
		
		this.owningPool = owningPool;
	}
	
	
	
	//=========//
	// setters //
	//=========//
	
	public void addByteArrayList(ByteArrayList list) { this.byteArrayLists.add(list); }
	public void addShortArrayList(ShortArrayList list) { this.shortArrayLists.add(list); }
	public void addLongArrayListRef(LongArrayList list, SoftReference<LongArrayList> listRef) 
	{
		this.longArrayLists.add(list);
		this.longArrayRefLists.add(listRef); 
	}
	
	
	
	//=========//
	// getters //
	//=========//
	
	public int getByteArrayCount() { return this.byteArrayLists.size(); }
	public int getShortArrayCount() { return this.shortArrayLists.size(); }
	public int getLongArrayCount() { return this.longArrayLists.size(); }
	
	
	
	public ByteArrayList getByteArray(int index, int size)
	{
		ByteArrayList list = this.byteArrayLists.get(index);
		ListUtil.clearAndSetSize(list, size);
		return list;
	}
	public ShortArrayList getShortArray(int index, int size)
	{
		ShortArrayList list = this.shortArrayLists.get(index);
		ListUtil.clearAndSetSize(list, size);
		return list;
	}
	public LongArrayList getLongArray(int index, int size)
	{
		LongArrayList list = this.longArrayLists.get(index);
		ListUtil.clearAndSetSize(list, size);
		return list;
	}
	
	public ArrayList<ByteArrayList> getAllByteArrays() { return this.byteArrayLists; }
	public ArrayList<ShortArrayList> getAllShortArrays() { return this.shortArrayLists; }
	public ArrayList<LongArrayList> getAllLongArrays() { return this.longArrayLists; }
	public ArrayList<SoftReference<LongArrayList>> getAllLongArrayRefs() { return this.longArrayRefLists; }
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override 
	public void close()
	{
		this.owningPool.returnCheckout(this);
	}
	
	
	
}
