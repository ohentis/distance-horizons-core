package com.seibel.distanthorizons.core.util.objects.dataStreams;

import net.jpountz.lz4.LZ4FrameOutputStream;

import java.io.*;

/**
 * See {@link DhDataInputStream} for more information about these custom streams.
 *
 * @see DhDataInputStream
 */
public class DhDataOutputStream extends DataOutputStream
{
	public DhDataOutputStream(OutputStream stream) throws IOException
	{
		super(new LZ4FrameOutputStream(new BufferedOutputStream(stream)));
	}
	
	@Override
	public void close() throws IOException { /* Do nothing. */ }
	
}
