package com.seibel.distanthorizons.core.util.objects.dataStreams;

import net.jpountz.lz4.LZ4FrameInputStream;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

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
	public DhDataInputStream(InputStream stream) throws IOException
	{
		super(new LZ4FrameInputStream(new BufferedInputStream(stream)));
	}
	
	@Override
	public void close() throws IOException { /* Do nothing. */ }
	
}
