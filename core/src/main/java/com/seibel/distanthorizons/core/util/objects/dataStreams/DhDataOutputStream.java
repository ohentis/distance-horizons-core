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

package com.seibel.distanthorizons.core.util.objects.dataStreams;

import com.github.luben.zstd.RecyclingBufferPool;
import com.github.luben.zstd.ZstdOutputStream;
import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.jpountz.xxhash.XXHashFactory;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.*;

/**
 * See {@link DhDataInputStream} for more information about these custom streams.
 *
 * @see DhDataInputStream
 */
public class DhDataOutputStream extends DataOutputStream
{
	public DhDataOutputStream(OutputStream stream, EDhApiDataCompressionMode compressionMode) throws IOException
	{ 
		super(warpStream(new BufferedOutputStream(stream), compressionMode)); 
	}
	private static OutputStream warpStream(OutputStream stream, EDhApiDataCompressionMode compressionMode) throws IOException
	{
		switch (compressionMode)
		{
			case UNCOMPRESSED:
				return stream;
			case LZ4:
				return new LZ4FrameOutputStream(stream, 
						LZ4FrameOutputStream.BLOCKSIZE.SIZE_64KB, -1L, 
						// using native instances reduces GC pressures
						LZ4Factory.nativeInstance().fastCompressor(),
						XXHashFactory.nativeInstance().hash32(), 
						LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE);
			case Z_STD:
				return new ZstdOutputStream(stream, RecyclingBufferPool.INSTANCE);
			case LZMA2:
				// in James' testing preset 4 has the best balance between compression ratio and speed
				// 5 is slightly more compressed 0.128 vs 0.139, but is roughly 60% slower
				return new XZOutputStream(stream, new LZMA2Options(4));
			
			default:
				throw new IllegalArgumentException("No compressor defined for ["+compressionMode+"]");
		}
	}
	
	@Override
	public void close() throws IOException { /* Do nothing. */ }
	
}
