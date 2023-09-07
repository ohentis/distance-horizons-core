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

package com.seibel.distanthorizons.core.file.metaData;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.Adler32;
import java.util.zip.CheckedOutputStream;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FileUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import org.apache.logging.log4j.Logger;

/**
 * This represents the data appended to any file we write. <br>
 * Contains a {@link BaseMetaData} which holds most of the necessary values written to the file. <br><br>
 *
 * Used size: 40 bytes <br>
 * Remaining space: 24 bytes <br>
 * Total size: 64 bytes <br><br><br>
 *
 *
 * <Strong>Metadata format: </Strong><br><br>
 * <code>
 * 4 bytes: metadata identifier bytes: "DHv0" (in ascii: 0x44 48 76 30) this signals the file is in the metadata format <br>
 * 4 bytes: section X position <br>
 * 4 bytes: section Y position (Unused, for future proofing) <br>
 * 4 bytes: section Z position <br> <br>
 *
 * 4 bytes: data checksum <br>
 * 1 byte: section detail level <br>
 * 1 byte: data detail level // Note: not sure if this is needed <br>
 * 1 byte: loader version <br>
 * 1 byte: unused <br> <br>
 *
 * 8 bytes: datatype identifier <br> <br>
 *
 * 8 bytes: data version
 * </code>
 */
public abstract class AbstractMetaDataContainerFile
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	public static final int METADATA_SIZE_IN_BYTES = 64;
	//    public static final int BUFFER_SIZE = 8192;
	public static final int METADATA_RESERVED_SIZE = 24;
	/** equivalent to "DHv0" */
	public static final int METADATA_IDENTITY_BYTES = 0x44_48_76_30;
	
	/**
	 * James tested this on windows (2023-02-18) and didn't have any issues,
	 * so it will be turned on for now. If there turns out to be issues
	 * we can always turn it off. <Br><br>
	 *
	 * original comment: <br>
	 * Currently set to false because for some reason
	 * Window is throwing PermissionDeniedException when trying to atomic replace a file...
	 */
	public static final boolean USE_ATOMIC_MOVE_REPLACE = true;
	
	
	/**
	 * Will be null if no file exists for this object. <br>
	 * NOTE: Only use {@link BaseMetaData#pos} when initially setting up this object, afterwards the standalone {@link AbstractMetaDataContainerFile#pos} should be used.
	 */
	public volatile BaseMetaData baseMetaData = null;
	
	/** Should be used instead of the position inside {@link AbstractMetaDataContainerFile#baseMetaData} */
	public final DhSectionPos pos;
	
	public File file;
	
	private volatile boolean DebugThreadCheck = false;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	/**
	 * Create a metaFile in this path.
	 *
	 * @throws FileAlreadyExistsException If the path already has a file.
	 */
	protected AbstractMetaDataContainerFile(File file, DhSectionPos pos) throws FileAlreadyExistsException
	{
		this.file = file;
		this.pos = pos;
		if (file.exists())
		{
			throw new FileAlreadyExistsException(file.toString());
		}
	}
	
	/**
	 * Creates an {@link AbstractMetaDataContainerFile} with the file at the given path.
	 *
	 * @throws IOException if the file was formatted incorrectly
	 * @throws FileNotFoundException if no file exists for the given path
	 */
	protected AbstractMetaDataContainerFile(File file) throws IOException, FileNotFoundException
	{
		this.file = file;
		if (!file.exists())
		{
			throw new FileNotFoundException("File not found at [" + file + "]");
		}
		
		validateMetaDataFile(this.file);
		this.baseMetaData = readMetaDataFromFile(file);
		this.pos = this.baseMetaData.pos;
	}
	/**
	 * Attempts to create a new {@link AbstractMetaDataContainerFile} from the given file.
	 *
	 * @throws IOException if the file was formatted incorrectly
	 */
	private static BaseMetaData readMetaDataFromFile(File file) throws IOException
	{
		try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ))
		{
			ByteBuffer byteBuffer = ByteBuffer.allocate(METADATA_SIZE_IN_BYTES);
			channel.read(byteBuffer, 0);
			channel.close();
			byteBuffer.flip();
			
			int idBytes = byteBuffer.getInt();
			if (idBytes != METADATA_IDENTITY_BYTES)
			{
				if (file.exists())
				{
					FileUtil.renameCorruptedFile(file);
					throw new IOException("Invalid file format: Metadata Identity byte check failed. Expected: [" + METADATA_IDENTITY_BYTES + "], Actual: [" + idBytes + "].");
				}
				else
				{
					throw new IOException("No file found for meta data. Expected file path: " + file.getPath());
				}
			}
			
			int x = byteBuffer.getInt();
			int y = byteBuffer.getInt(); // Unused
			int z = byteBuffer.getInt();
			int checksum = byteBuffer.getInt();
			byte detailLevel = byteBuffer.get();
			byte dataLevel = byteBuffer.get();
			byte loaderVersion = byteBuffer.get();
			EDhApiWorldGenerationStep worldGenStep = EDhApiWorldGenerationStep.fromValue(byteBuffer.get());
			long dataTypeId = byteBuffer.getLong();
			long dataVersion = byteBuffer.getLong(); // data versioning
			LodUtil.assertTrue(byteBuffer.remaining() == METADATA_RESERVED_SIZE);
			DhSectionPos dataPos = new DhSectionPos(detailLevel, x, z);
			
			return new BaseMetaData(dataPos, checksum, dataLevel, worldGenStep, dataTypeId, loaderVersion, dataVersion);
		}
	}
	
	
	
	//==============//
	// file writing //
	//==============//
	
	protected void writeData(IMetaDataWriterFunc<DhDataOutputStream> dataWriterFunc) throws IOException
	{
		LodUtil.assertTrue(!this.DebugThreadCheck);
		this.DebugThreadCheck = true;
		LodUtil.assertTrue(this.baseMetaData != null);
		if (this.file.exists())
		{
			validateMetaDataFile(this.file);
		}
		
		
		
		File tempFile;
		if (USE_ATOMIC_MOVE_REPLACE)
		{
			tempFile = new File(this.file.getPath() + ".tmp");
			//tempFile.deleteOnExit();
		}
		else
		{
			tempFile = this.file;
		}
		
		try (FileChannel fileChannel = FileChannel.open(tempFile.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
		{
			fileChannel.position(METADATA_SIZE_IN_BYTES);
			
			// the order of these streams is important, otherwise the checksum won't be calculated
			CheckedOutputStream checkedOut = new CheckedOutputStream(Channels.newOutputStream(fileChannel), new Adler32());
			// normally a DhStream should be the topmost stream to prevent closing the stream accidentally, but since this stream will be closed immediately after writing anyway, it won't be an issue
			DhDataOutputStream compressedOut = new DhDataOutputStream(checkedOut);
			
			// write the contained data
			dataWriterFunc.writeBufferToFile(compressedOut);
			compressedOut.flush();
			this.baseMetaData.checksum = (int) checkedOut.getChecksum().getValue();
			
			
			// Write metadata
			fileChannel.position(0);
			ByteBuffer buffer = ByteBuffer.allocate(METADATA_SIZE_IN_BYTES);
			buffer.putInt(METADATA_IDENTITY_BYTES);
			buffer.putInt(this.pos.sectionX);
			buffer.putInt(Integer.MIN_VALUE); // Unused - y pos
			buffer.putInt(this.pos.sectionZ);
			buffer.putInt(this.baseMetaData.checksum);
			buffer.put(this.pos.sectionDetailLevel);
			buffer.put(this.baseMetaData.dataLevel);
			buffer.put(this.baseMetaData.binaryDataFormatVersion);
			buffer.put(this.baseMetaData.worldGenStep != null ? this.baseMetaData.worldGenStep.value : EDhApiWorldGenerationStep.EMPTY.value); // TODO this null check shouldn't be necessary
			buffer.putLong(this.baseMetaData.dataTypeId);
			buffer.putLong(this.baseMetaData.dataVersion.get()); // for types that doesn't need data versioning, this will be Long.MAX_VALUE
			LodUtil.assertTrue(buffer.remaining() == METADATA_RESERVED_SIZE);
			buffer.flip();
			fileChannel.write(buffer);
			
			
			fileChannel.close();
			//LOGGER.info("Saved file: "+this.file.getName());
			
			if (USE_ATOMIC_MOVE_REPLACE)
			{
				// Atomic move / replace the actual file
				Files.move(tempFile.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING); // TODO couldn't StandardCopyOption. also work here?
				//LOGGER.info("replaced file: "+this.file.toPath());
			}
		}
		catch (NoSuchFileException e)
		{
			// can be thrown by the "Files.move" method if the system tries writing to an unloaded level
		}
		catch (ClosedChannelException e) // includes ClosedByInterruptException
		{
			// expected if the file handler is shut down, the exception can be ignored
			//LOGGER.warn(AbstractMetaDataContainerFile.class.getSimpleName()+" file writing interrupted. Error: "+e.getMessage());
		}
		finally
		{
			String tempDeleteErrorMessage = null;
			try
			{
				// Delete temp file if it exists (this generally means there was an issue saving)
				if (USE_ATOMIC_MOVE_REPLACE && tempFile.exists())
				{
					boolean fileRemoved = tempFile.delete();
					if (!fileRemoved)
					{
						tempDeleteErrorMessage = "Unable to remove Temporary file at: " + tempFile.getPath();
					}
				}
			}
			catch (SecurityException exception)
			{
				tempDeleteErrorMessage = "Security error: [" + exception.getMessage() + "] when attempting to remove Temporary file at: " + tempFile.getPath();
			}
			
			if (tempDeleteErrorMessage != null)
			{
				LOGGER.error(tempDeleteErrorMessage);
			}
			this.DebugThreadCheck = false;
		}
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	/** Throws an {@link IOException} if the given file isn't valid */
	private static void validateMetaDataFile(File file) throws IOException
	{
		if (!file.exists()) throw new IOException("File missing");
		if (!file.isFile()) throw new IOException("Not a file");
		if (!file.canRead()) throw new IOException("File not readable");
		if (!file.canWrite()) throw new IOException("File not writable");
	}
	
	/** Sets this object's {@link AbstractMetaDataContainerFile#baseMetaData} using the set {@link AbstractMetaDataContainerFile#file} */
	protected void loadMetaData() throws IOException
	{
		validateMetaDataFile(this.file);
		this.baseMetaData = readMetaDataFromFile(this.file);
		if (!this.baseMetaData.pos.equals(this.pos))
		{
			LOGGER.warn("The file is from a different location than expected! Expected: [" + this.pos + "] but got [" + this.baseMetaData.pos + "]. Ignoring file tag.");
			this.baseMetaData.pos = this.pos;
		}
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	@FunctionalInterface
	public interface IMetaDataWriterFunc<T> { void writeBufferToFile(T t) throws IOException; }
	
}
