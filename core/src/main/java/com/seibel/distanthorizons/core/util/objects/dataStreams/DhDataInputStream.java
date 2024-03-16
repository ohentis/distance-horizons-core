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

import com.github.luben.zstd.ZstdInputStream;
import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import net.jpountz.lz4.LZ4FrameInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.*;

/**
 * Combines multiple different streams together for ease of use
 * and to prevent accidentally wrapping a stream twice or passing in
 * the wrong stream. <br><br>
 *
 * <strong>Note:</strong>
 * This stream cannot be closed,
 * the passed in stream must be closed instead.
 * This is done to prevent closing file channels prematurely while accessing them.
 */
public class DhDataInputStream extends DataInputStream
{
	public DhDataInputStream(InputStream stream, EDhApiDataCompressionMode compressionMode) throws IOException
	{ 
		super(warpStream(new BufferedInputStream(stream), compressionMode)); 
	}
	private static InputStream warpStream(InputStream stream, EDhApiDataCompressionMode compressionMode) throws IOException
	{
		switch (compressionMode)
		{
			case UNCOMPRESSED:
				return stream;
			case LZ4:
				return new LZ4FrameInputStream(stream);
			case Z_STD:
				return new ZstdInputStream(stream);
			case LZMA2:
				// Note: all LZMA/XZ compressors can be decompressed using this same InputStream
				return new XZInputStream(stream);
			
			default:
				throw new IllegalArgumentException("No compressor defined for ["+compressionMode+"]");
		}
	}
	
	@Override
	public void close() throws IOException { /* Do nothing. */ }
	
}
