package com.seibel.distanthorizons.core.file.subDimMatching;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.SingleColumnFullDataAccessor;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataFileHandler;
import com.seibel.distanthorizons.core.file.fullDatafile.IFullDataSourceProvider;
import com.seibel.distanthorizons.core.file.structure.ClientOnlySaveStructure;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import org.apache.logging.log4j.LogManager;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Used to allow multiple levels using the same dimension type. <br/>
 * This is specifically needed for servers running the Multiverse plugin (or similar).
 * 
 * @author James Seibel
 * @version 12-17-2022
 */
public class SubDimensionLevelMatcher implements AutoCloseable
{
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	public static final ConfigBasedLogger LOGGER = new ConfigBasedLogger(LogManager.getLogger(),
			() -> Config.Client.Advanced.Logging.logFileSubDimEvent.get());
	
	private final ExecutorService matcherThread = ThreadUtil.makeSingleThreadPool("Sub Dimension Matcher");
	
	private SubDimensionPlayerData playerData = null;
	private SubDimensionPlayerData firstSeenPlayerData = null;
	
	/** If true the LodDimensionFileHelper is attempting to determine the folder for this dimension */
	private final AtomicBoolean determiningWorldFolder = new AtomicBoolean(false);
	private final ILevelWrapper currentLevel;
	private volatile File foundLevel = null;
	private final File[] potentialFiles;
	private final File levelsFolder;
	
	
	
	public SubDimensionLevelMatcher(ILevelWrapper targetWorld, File levelsFolder, File[] potentialFiles)
	{
		this.currentLevel = targetWorld;
		this.potentialFiles = potentialFiles;
		this.levelsFolder = levelsFolder;
		
		if (potentialFiles.length == 0)
		{
			String newId = UUID.randomUUID().toString();
			LOGGER.info("No potential level files found. Creating a new sub dimension with the ID [{}]...",
					LodUtil.shortenString(newId, 8));
			this.foundLevel = new File(levelsFolder, newId);
		}
	}
	
	
	
	public boolean isFindingLevel(ILevelWrapper level) { return Objects.equals(level, this.currentLevel); }
	
	/** May return null if the level isn't known yet */
	public File tryGetLevel()
	{
		this.tryGetLevelInternal();
		return this.foundLevel;
	}
	private void tryGetLevelInternal()
	{
		if (this.foundLevel != null)
		{
			return;
		}
		
		// prevent multiple threads running at the same time
		if (this.determiningWorldFolder.getAndSet(true))
		{
			return;
		}
		
		
		this.matcherThread.submit(() ->
		{
			try
			{
				// attempt to get the file handler
				File saveDir = this.attemptToDetermineSubDimensionFolder();
				if (saveDir != null)
				{
					this.foundLevel = saveDir;
				}
			}
			catch (IOException e)
			{
				LOGGER.error("Unable to set the dimension file handler for level [" + this.currentLevel + "]. Error: ", e);
			}
			finally
			{
				// make sure we unlock this method
				this.determiningWorldFolder.set(false);
			}
		});
	}
	
	/**
	 * Currently this method checks a single chunk (where the player is)
	 * and compares it against the same chunk position in the other dimension worlds to
	 * guess which world the player is in.
	 * @throws IOException if the folder doesn't exist or can't be accessed
	 */
	public File attemptToDetermineSubDimensionFolder() throws IOException
	{
		{ // Update PlayerData
			SubDimensionPlayerData data = SubDimensionPlayerData.tryGetPlayerData(MC_CLIENT);
			if (data != null)
			{
				if (this.firstSeenPlayerData == null)
				{
					this.firstSeenPlayerData = data;
				}
				this.playerData = data;
			}
		}
		
		// relevant positions
		DhChunkPos playerChunkPos = new DhChunkPos(this.playerData.playerBlockPos);
		int startingBlockPosX = playerChunkPos.getMinBlockX();
		int startingBlockPosZ = playerChunkPos.getMinBlockZ();
		
		// chunk from the newly loaded level
		IChunkWrapper newlyLoadedChunk = MC_CLIENT.getWrappedClientWorld().tryGetChunk(playerChunkPos);
		// check if this chunk is valid to test
		if (!this.CanDetermineLevelFolder(newlyLoadedChunk))
		{
			return null;
		}
		
		//TODO: Compute a ChunkData from current chunk.
        
        // generate a LOD to test against
        boolean lodGenerated = LodDataBuilder.canGenerateLodFromChunk(newlyLoadedChunk);
        if (!lodGenerated)
            return null;

        // log the start of this attempt
        LOGGER.info("Attempting to determine sub-dimension for [" + MC_CLIENT.getWrappedClientWorld().getDimensionType().getDimensionName() + "]");
        LOGGER.info("Player block pos in dimension: [" + playerData.playerBlockPos.getX() + "," + playerData.playerBlockPos.getY() + "," + playerData.playerBlockPos.getZ() + "]");

        // new chunk data
		ChunkSizedFullDataAccessor newChunkSizedFullDataView = LodDataBuilder.createChunkData(newlyLoadedChunk);
		long[][][] newChunkData = new long[LodUtil.CHUNK_WIDTH][LodUtil.CHUNK_WIDTH][];
		if (newChunkSizedFullDataView != null)
		{
			for (int x = 0; x < LodUtil.CHUNK_WIDTH; x++)
			{
				for (int z = 0; z < LodUtil.CHUNK_WIDTH; z++)
				{
					long[] array = newChunkSizedFullDataView.get(x, z).getRaw();
					newChunkData[x][z] = array;
				}
			}
		}
        boolean newChunkHasData = newChunkSizedFullDataView != null && newChunkSizedFullDataView.nonEmptyCount() != 0;

        // check if the chunk is actually empty
        if (!newChunkHasData)
        {
            if (newlyLoadedChunk.getHeight() != 0)
            {
                // the chunk isn't empty but the LOD is...

                String message = "Error: the chunk at (" + playerChunkPos.getX() + "," + playerChunkPos.getZ() + ") has a height of [" + newlyLoadedChunk.getHeight() + "] but the LOD generated is empty!";
                LOGGER.error(message);
            }
            else
            {
                String message = "Warning: The chunk at (" + playerChunkPos.getX() + "," + playerChunkPos.getZ() + ") is empty.";
                LOGGER.warn(message);
            }
            return null;
        }
		
		
		// compare each world with the newly loaded one
		SubDimCompare mostSimilarSubDim = null;
		
		File[] levelFolders = potentialFiles;
		LOGGER.info("Potential Sub Dimension folders: [" + levelFolders.length + "]");
		for (File testLevelFolder : levelFolders)
		{
			LOGGER.info("Testing level folder: [" + LodUtil.shortenString(testLevelFolder.getName(), 8) + "]");
			try
			{
				// TODO: Try load a data file overlapping the playerChunkPos from ClientOnlySaveStructure,
				//  and then use it to compare chunk data to current chunk.
				
				// get a data source for this dimension
				IClientLevelWrapper clientLevelWrapper = null;
				if (clientLevelWrapper == null)
				{
					// TODO Sub dimension level matcher is incomplete
					LOGGER.info(this.getClass().getSimpleName() + " implementation incomplete. Unable to get LOD data file from generic folder without [" + IClientLevelWrapper.class.getSimpleName() + "].");
					break;
				}
				IDhLevel tempLevel = null; // new DhClientLevel(new ClientOnlySaveStructure(), clientLevelWrapper, ???);
				IFullDataSourceProvider fileHandler = new FullDataFileHandler(tempLevel, tempLevel.getSaveStructure());
				CompletableFuture<IFullDataSource> testDataSource = fileHandler.read(new DhSectionPos(playerChunkPos));
				IFullDataSource lodDataSource = testDataSource.get();
				
				
				// convert the data source into a raw LOD data array
				long[][][] testChunkData = new long[LodUtil.CHUNK_WIDTH][LodUtil.CHUNK_WIDTH][];
				boolean testLodDataExists = false;
				for (int x = 0; x < LodUtil.CHUNK_WIDTH; x++)
				{
					for (int z = 0; z < LodUtil.CHUNK_WIDTH; z++)
					{
						SingleColumnFullDataAccessor singleDataColumn = lodDataSource.tryGet(x, z);
						if (singleDataColumn != null)
						{
							long[] rawSingleColumn = singleDataColumn.getRaw();
							testChunkData[x][z] = rawSingleColumn;
							
							
							// does any LOD data exist in this chunk?
							// if we have found at least one datapoint, don't check again
							if (!testLodDataExists)
							{
								// does any data exist in this column?
								for (long dataPoint : rawSingleColumn)
								{
									if (dataPoint != FullDataPointUtil.EMPTY_DATA_POINT)
									{
										// at least one datapoint exists in this chunk
										testLodDataExists = true;
										break;
									}
								}
							}
						}
					}
				}
				
				
				// stop if the test chunk doesn't contain any data
				if (!testLodDataExists)
				{
					String message = "The test chunk for dimension folder [" + LodUtil.shortenString(testLevelFolder.getName(), 8) + "] and chunk pos (" + playerChunkPos.getX() + "," + playerChunkPos.getZ() + ") is empty. This is expected if the position is outside the sub-dimension's generated area.";
					LOGGER.info(message);
					continue;
				}
				
				
				// get the player data for this dimension folder
				SubDimensionPlayerData testPlayerData = new SubDimensionPlayerData(testLevelFolder);
				LOGGER.info("Last known player pos: [" + testPlayerData.playerBlockPos.getX() + "," + testPlayerData.playerBlockPos.getY() + "," + testPlayerData.playerBlockPos.getZ() + "]");
				
				// check if the block positions are close
				int playerBlockDist = testPlayerData.playerBlockPos.getManhattanDistance(playerData.playerBlockPos);
				LOGGER.info("Player block position distance between saved sub dimension and first seen is [" + playerBlockDist + "]");
				
				
				
				// compare the two LODs
				int equalDataPoints = 0;
				int totalDataPointCount = 0;
				for (int x = 0; x < LodUtil.CHUNK_WIDTH; x++)
				{
					for (int z = 0; z < LodUtil.CHUNK_WIDTH; z++)
					{
						for (int y = 0; y < newChunkData[x][z].length; y++)
						{
							if (newChunkData[x][z][y] == testChunkData[x][z][y])
							{
								equalDataPoints++;
							}
							totalDataPointCount++;
						}
					}
				}
				
				// determine if this world is closer to the newly loaded world
				SubDimCompare subDimCompare = new SubDimCompare(equalDataPoints, totalDataPointCount, playerBlockDist, testLevelFolder);
				if (mostSimilarSubDim == null || subDimCompare.compareTo(mostSimilarSubDim) > 0)
				{
					mostSimilarSubDim = subDimCompare;
				}
				
				LOGGER.info("Sub dimension [" + LodUtil.shortenString(testLevelFolder.getName(), 8) + "...] is current dimension probability: " + LodUtil.shortenString(subDimCompare.getPercentEqual() + "", 5) + " (" + equalDataPoints + "/" + totalDataPointCount + ")");
			}
			catch (Exception e)
			{
				// this sub dimension isn't formatted correctly
				// for now we are just assuming it is an unrelated file
			}
		}
		
		// TODO if two sub dimensions contain the same LODs merge them???
		
		// the first seen player data is no longer needed, the sub dimension has been determined
		this.firstSeenPlayerData = null;
		
		if (mostSimilarSubDim != null && mostSimilarSubDim.isValidSubDim())
		{
			// we found a world folder that is similar, use it
			
			LOGGER.info("Sub Dimension set to: [" + LodUtil.shortenString(mostSimilarSubDim.folder.getName(), 8) + "...] with an equality of [" + mostSimilarSubDim.getPercentEqual() + "]");
			return mostSimilarSubDim.folder;
		}
		else
		{
			// no world folder was found, create a new one
			
			double highestEqualityPercent = mostSimilarSubDim != null ? mostSimilarSubDim.getPercentEqual() : 0;
			
			String newId = UUID.randomUUID().toString();
			String message = "No suitable sub dimension found. The highest equality was [" + LodUtil.shortenString(highestEqualityPercent + "", 5) + "]. Creating a new sub dimension with ID: " + LodUtil.shortenString(newId, 8) + "...";
			LOGGER.info(message);
			File folder = new File(this.levelsFolder, newId);
			folder.mkdirs();
			return folder;
		}
	}
	
	/** Returns true if the given chunk is valid to test */
	public boolean CanDetermineLevelFolder(IChunkWrapper chunk)
	{
		// we can only guess if the given chunk can be converted into a LOD
		return LodDataBuilder.canGenerateLodFromChunk(chunk);
	}
	
	
	@Override
	public void close()
	{
		this.matcherThread.shutdownNow();
	}
	
}
