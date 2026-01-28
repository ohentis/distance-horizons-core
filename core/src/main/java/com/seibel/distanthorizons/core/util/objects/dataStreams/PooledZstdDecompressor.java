package com.seibel.distanthorizons.core.util.objects.dataStreams;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdException;
import com.seibel.distanthorizons.core.pooling.PhantomArrayListCheckout;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;

public class PooledZstdDecompressor
{
	/**
	 * Replaces {@link Zstd#decompress} so we can use a pooled byte array
	 * which significantly reduces GC pressure.
	 */
	public static byte[] decompressFrame(byte[] src, PhantomArrayListCheckout checkout) throws ZstdException
	{
		int compressedSize = (int) Zstd.findFrameCompressedSize(src, 0);
		int contentSize = (int) Zstd.getFrameContentSize(src, 0, compressedSize);
		if (Zstd.isError(contentSize))
		{
			// known error at the moment, but not for getErrorName
			if (contentSize == -1)
			{
				throw new ZstdException(contentSize, "Content size is unknown");
			}
			// otherwise let ZstdException get error message itself
			throw new ZstdException(contentSize);
		}
		
		ByteArrayList destination = checkout.getByteArray(0, contentSize);
		return decompress(src, compressedSize, contentSize, destination);
	}
	private static byte[] decompress(byte[] src, int srcSize, int originalSize, ByteArrayList destination) throws ZstdException
	{
		if (originalSize < 0)
		{
			throw new ZstdException(Zstd.errGeneric(), "Original size should not be negative");
		}
		
		int size;
		try(ZstdDecompressCtx ctx = new ZstdDecompressCtx())
		{
			size = ctx.decompressByteArray(destination.elements(), 0, destination.size(), src, 0, srcSize);
		}
		
		if (size != originalSize)
		{
			//return Arrays.copyOfRange(destination.elements(), 0, size);
			destination.size(size); // this assumes the size will only be smaller than the expected
		}
		
		return destination.elements();
	}
	
}
