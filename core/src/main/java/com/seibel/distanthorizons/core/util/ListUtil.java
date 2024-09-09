package com.seibel.distanthorizons.core.util;

import java.util.ArrayList;

public class ListUtil
{
	/** Create list filled with null up to the size */
	public static <T> ArrayList<T> createEmptyList(int size)
	{
		ArrayList<T> list = new ArrayList<T>();
		for (int i = 0; i < size; i++)
		{
			list.add(null);	
		}
		return list;
	}
	
	
}
