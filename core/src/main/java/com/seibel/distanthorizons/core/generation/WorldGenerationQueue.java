package com.seibel.distanthorizons.core.generation;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.dataObjects.fullData.accessor.ChunkSizedFullDataAccessor;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.generation.tasks.*;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhLodPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder;
import com.seibel.distanthorizons.core.file.fullDatafile.FullDataFileHandler;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.objects.DhThreadFactory;
import com.seibel.distanthorizons.core.util.objects.RateLimitedThreadPoolExecutor;
import com.seibel.distanthorizons.core.util.objects.UncheckedInterruptedException;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.io.Closeable;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class WorldGenerationQueue implements IWorldGenerationQueue, IDebugRenderable
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	public static final DhThreadFactory THREAD_FACTORY = new DhThreadFactory(ThreadUtil.THREAD_NAME_PREFIX + "World-Gen-Worker-Thread", Thread.MIN_PRIORITY);
	
	private final IDhApiWorldGenerator generator;
	
	/** contains the positions that need to be generated */
	//private final QuadTree<WorldGenTask> waitingTaskQuadTree;
	private final ConcurrentHashMap<DhLodPos, WorldGenTask> waitingTasks = new ConcurrentHashMap<>();
	
	private final ConcurrentHashMap<DhLodPos, InProgressWorldGenTaskGroup> inProgressGenTasksByLodPos = new ConcurrentHashMap<>();
	
	// granularity is the detail level for batching world generator requests together
	public final byte maxGranularity;
	public final byte minGranularity;
	
	/** largest numerical detail level allowed */
	public final byte largestDataDetail;
	@Override
	public byte largestDataDetail() { return this.largestDataDetail; }
	/** lowest numerical detail level allowed */
	public final byte smallestDataDetail;
	
	/** If not null this generator is in the process of shutting down */
	private volatile CompletableFuture<Void> generatorClosingFuture = null;
	
	// TODO this logic isn't great and can cause a limit to how many threads could be used for world generation, 
	//  however it won't cause duplicate requests or concurrency issues, so it will be good enough for now.
	//  A good long term fix may be to either:
	//  1. allow the generator to deal with larger sections (let the generator threads split up larger tasks into smaller one
	//  2. batch requests better. instead of sending 4 individual tasks of detail level N, send 1 task of detail level n+1
	private final ExecutorService queueingThread = ThreadUtil.makeSingleThreadPool("World Gen Queue");
	private boolean generationQueueStarted = false;
	private DhBlockPos2D generationTargetPos = DhBlockPos2D.ZERO;
	/** can be used for debugging how many tasks are currently in the queue */
	private int numberOfTasksQueued = 0;
	
	// debug variables to test for duplicate world generator requests //
	/** limits how many of the previous world gen requests we should track */
	private static final int MAX_ALREADY_GENERATED_COUNT = 100;
	private final HashMap<DhLodPos, StackTraceElement[]> alreadyGeneratedPosHashSet = new HashMap<>(MAX_ALREADY_GENERATED_COUNT);
	private final Queue<DhLodPos> alreadyGeneratedPosQueue = new LinkedList<>();
	
	private static RateLimitedThreadPoolExecutor worldGeneratorThreadPool;
	private static ConfigChangeListener<Integer> configListener;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public WorldGenerationQueue(IDhApiWorldGenerator generator)
	{
		LOGGER.info("Creating world gen queue");
		this.generator = generator;
		this.maxGranularity = generator.getMaxGenerationGranularity();
		this.minGranularity = generator.getMinGenerationGranularity();
		this.largestDataDetail = generator.getLargestDataDetailLevel();
		this.smallestDataDetail = generator.getSmallestDataDetailLevel();
		
		//FIXME: Currently resizing view dist doesn't update this, causing some gen task to fail.
		int treeWidth = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistance.get() * LodUtil.CHUNK_WIDTH * 2; // TODO the *2 is to allow for generation edge cases, and should probably be removed at some point
		byte treeMinDetailLevel = LodUtil.CHUNK_DETAIL_LEVEL; // The min level should be at least fill in 1 ChunkSizedFullDataAccessor.
		//this.waitingTaskQuadTree = new QuadTree<>(treeWidth, DhBlockPos2D.ZERO /*the quad tree will be re-centered later*/, treeMinDetailLevel);
		
		
		if (this.minGranularity < LodUtil.CHUNK_DETAIL_LEVEL)
		{
			throw new IllegalArgumentException(IDhApiWorldGenerator.class.getSimpleName() + ": min granularity must be at least 4 (Chunk sized)!");
		}
		if (this.maxGranularity < this.minGranularity)
		{
			throw new IllegalArgumentException(IDhApiWorldGenerator.class.getSimpleName() + ": max granularity smaller than min granularity!");
		}
		DebugRenderer.register(this);
		LOGGER.info("Created world gen queue");
	}
	
	
	
	//=================//
	// world generator //
	// task handling   //
	//=================//
	
	public CompletableFuture<WorldGenResult> submitGenTask(DhLodPos pos, byte requiredDataDetail, IWorldGenTaskTracker tracker)
	{
		// the generator is shutting down, don't add new tasks
		if (this.generatorClosingFuture != null)
		{
			return CompletableFuture.completedFuture(WorldGenResult.CreateFail());
		}
		
		
		// make sure the generator can provide the requested position
		if (requiredDataDetail < this.smallestDataDetail)
		{
			throw new UnsupportedOperationException("Current generator does not meet requiredDataDetail level");
		}
		if (requiredDataDetail > this.largestDataDetail)
		{
			requiredDataDetail = this.largestDataDetail;
		}
		
		// Assert that the data at least can fill in 1 single ChunkSizedFullDataAccessor
		LodUtil.assertTrue(pos.detailLevel > requiredDataDetail + LodUtil.CHUNK_DETAIL_LEVEL);
		
		DhSectionPos requestPos = new DhSectionPos(pos.detailLevel, pos.x, pos.z);
		
		
		//if (this.waitingTaskQuadTree.isSectionPosInBounds(requestPos))
		{
			CompletableFuture<WorldGenResult> future = new CompletableFuture<>();
			//this.waitingTaskQuadTree.setValue(requestPos, new WorldGenTask(pos, requiredDataDetail, tracker, future));
			waitingTasks.put(pos, new WorldGenTask(pos, requiredDataDetail, tracker, future));
			return future;
		}
		//else
		//{
		//return CompletableFuture.completedFuture(WorldGenResult.CreateFail());
		//}
	}
	
	@Override
	public void cancelGenTasks(Iterable<DhSectionPos> positions)
	{
		// TODO Should we cancel generation of chunks that were loaded by the player?
	}
	
	//===============//
	// running tasks //
	//===============//
	
	public void runCurrentGenTasksUntilBusy(DhBlockPos2D targetPos)
	{
		generator.preGeneratorTaskStart();
		try
		{
			// the generator is shutting down, don't attempt to generate anything
			if (this.generatorClosingFuture != null)
			{
				return;
			}
			
			
			// update the target pos
			this.generationTargetPos = targetPos;
			
			// only start the queuing thread once
			if (!generationQueueStarted)
			{
				startWorldGenQueuingThread();
			}
		}
		catch (Exception e)
		{
			LOGGER.error(e.getMessage(), e);
		}
	}
	private void startWorldGenQueuingThread()
	{
		this.generationQueueStarted = true;
		
		// queue world generation tasks on its own thread since this process is very slow and would lag the server thread
		this.queueingThread.execute(() ->
		{
			try
			{
				// loop until the generator is shutdown
				while (!Thread.interrupted())
				{
//					LOGGER.info("pre task count: " + this.numberOfTasksQueued);
					
					// recenter the generator tasks, this is done to prevent generating chunks where the player isn't
					//this.waitingTaskQuadTree.setCenterBlockPos(this.generationTargetPos);
					
					// queue generation tasks until the generator is full, or there are no more tasks to generate
					boolean taskStarted = true;
					while (!this.generator.isBusy() && taskStarted)
					{
						//this.removeGarbageCollectedTasks(); // TODO this is extremely slow
						taskStarted = this.startNextWorldGenTask(this.generationTargetPos);
						if (!taskStarted)
						{
							int debugPointOne = 0;
						}
					}


//					LOGGER.info("after task count: " + this.numberOfTasksQueued);
					
					// if there aren't any new tasks, wait a second before checking again // TODO replace with a listener instead
					Thread.sleep(1000);
				}
			}
			catch (InterruptedException e)
			{
				/* do nothing, this means the thread is being shut down */
			}
			catch (Exception e)
			{
				LOGGER.error("queueing exception: " + e.getMessage(), e);
				this.generationQueueStarted = false;
			}
		});
	}

//	/** Removes all {@link WorldGenTask}'s and {@link WorldGenTaskGroup}'s that have been garbage collected. */
//	private void removeGarbageCollectedTasks() // TODO remove, potential mystery errors caused by garbage collection isn't worth it (and may not be necessary any more now that we are using a quad tree to hold the tasks). // also this is very slow with the curent quad tree impelmentation
//	{
//		for (byte detailLevel = QuadTree.TREE_LOWEST_DETAIL_LEVEL; detailLevel < this.waitingTaskQuadTree.treeMaxDetailLevel; detailLevel++)
//		{
//			MovableGridRingList<WorldGenTask> gridRingList = this.waitingTaskQuadTree.getRingList(detailLevel);
//			Iterator<WorldGenTask> taskIterator = gridRingList.iterator();
//			while (taskIterator.hasNext())
//			{
//				// go through each WorldGenTask in the TaskGroup
//				WorldGenTask genTask = taskIterator.next();
//				if (genTask != null && !genTask.taskTracker.isMemoryAddressValid())
//				{
//					taskIterator.remove();
//					genTask.future.complete(WorldGenResult.CreateFail());
//				}
//			}
//		}
//	}
	
	private final Set<WorldGenTask> CheckingTasks = Collections.newSetFromMap(new ConcurrentHashMap<>());
	
	private static class Mapper
	{
		public final WorldGenTask task;
		public final int dist;
		public Mapper(WorldGenTask task, int dist)
		{
			this.task = task;
			this.dist = dist;
		}
		
	}
	
	/**
	 * @param targetPos the position to center the generation around
	 * @return false if no tasks were found to generate
	 */
	private boolean startNextWorldGenTask(DhBlockPos2D targetPos)
	{
		long closestGenDist = Long.MAX_VALUE;
		
		WorldGenTask closestTask = null;
		//CheckingTasks.clear();
		
/*		// TODO improve, having to go over every node isn't super efficient, removing null nodes from the tree would help
		Iterator<QuadNode<WorldGenTask>> nodeIterator = this.waitingTaskQuadTree.nodeIterator();
		while (nodeIterator.hasNext())
		{
			QuadNode<WorldGenTask> taskNode = nodeIterator.next();
			WorldGenTask newGenTask = taskNode.value;
			DhSectionPos taskSectionPos = taskNode.sectionPos;
			
			if (newGenTask != null) // TODO add an option to skip leaves with null values and potentially auto-prune them
			{
				CheckingTasks.add(newGenTask);
				if (!newGenTask.StillValid())
				{
					// skip and remove out-of-bound tasks or tasks that are no longer valid
					taskNode.value = null;
					continue;
				}
				
				
				// use chebyShev distance in order to generate in rings around the target pos (also because it is a fast distance calculation)
				int chebDistToTargetPos = newGenTask.pos.getCenterBlockPos().toPos2D().chebyshevDist(targetPos.toPos2D());
				if (chebDistToTargetPos < closestGenDist)
				{
					// this task is closer than the last one
					closestTask = newGenTask;
					closestGenDist = chebDistToTargetPos;
				}
			}
		}*/
		
		waitingTasks.forEach((pos, task) -> {
			if (!task.StillValid())
			{
				waitingTasks.remove(pos);
				task.future.complete(WorldGenResult.CreateFail());
			}
		});
		
		if (waitingTasks.size() == 0)
		{
			return false;
		}
		
		Mapper closestTaskMap = waitingTasks.reduceEntries(1024,
				v -> new Mapper(v.getValue(), v.getValue().pos.getCenterBlockPos().toPos2D().chebyshevDist(targetPos.toPos2D())),
				(a, b) -> a.dist < b.dist ? a : b);
		
		closestTask = closestTaskMap.task;
		
		// remove the task we found, we are going to start it and don't want to run it multiple times
		//WorldGenTask removedWorldGenTask = this.waitingTaskQuadTree.setValue(new DhSectionPos(closestTask.pos.detailLevel, closestTask.pos.x, closestTask.pos.z), null);
		waitingTasks.remove(closestTask.pos, closestTask);
		
		// do we need to modify this task to generate it?
		if (this.canGeneratePos((byte) 0, closestTask.pos)) // TODO should detail level 0 be replaced?
		{
			// detail level is correct for generation, start generation
			
			WorldGenTaskGroup closestTaskGroup = new WorldGenTaskGroup(closestTask.pos, (byte) 0);  // TODO should 0 be replaced?
			closestTaskGroup.worldGenTasks.add(closestTask); // TODO
			
			InProgressWorldGenTaskGroup newInProgressTask = new InProgressWorldGenTaskGroup(closestTaskGroup);
			InProgressWorldGenTaskGroup previousInProgressTask = this.inProgressGenTasksByLodPos.putIfAbsent(closestTask.pos, newInProgressTask);
			if (previousInProgressTask == null)
			{
				// no task exists for this position, start one
				this.startWorldGenTaskGroup(newInProgressTask);
			}
			else
			{
				// TODO replace the previous inProgress task if one exists
				// Note: Due to concurrency reasons, even if the currently running task is compatible with 
				// 		   the newly selected task, we cannot use it,
				//         as some chunks may have already been written into.
				
				LOGGER.warn("A task already exists for this position, todo: {}", closestTask.pos);
			}
			
			// a task has been started
			return true;
		}
		else
		{
			// detail level is too high (if the detail level was too low, the generator would've ignored the request),
			// split up the task
			
			
			// split up the task and add each one to the tree
			LinkedList<CompletableFuture<WorldGenResult>> childFutures = new LinkedList<>();
			DhSectionPos sectionPos = new DhSectionPos(closestTask.pos.detailLevel, closestTask.pos.x, closestTask.pos.z);
			WorldGenTask finalClosestTask = closestTask;
			sectionPos.forEachChild((childDhSectionPos) ->
			{
				CompletableFuture<WorldGenResult> newFuture = new CompletableFuture<>();
				childFutures.add(newFuture);
				
				WorldGenTask newGenTask = new WorldGenTask(new DhLodPos(childDhSectionPos.sectionDetailLevel, childDhSectionPos.sectionX, childDhSectionPos.sectionZ), childDhSectionPos.sectionDetailLevel, finalClosestTask.taskTracker, newFuture);
				waitingTasks.put(newGenTask.pos, newGenTask);
				//this.waitingTaskQuadTree.setValue(new DhSectionPos(childDhSectionPos.sectionDetailLevel, childDhSectionPos.sectionX, childDhSectionPos.sectionZ), newGenTask);
				
				//boolean valueAdded = this.waitingTaskQuadTree.getValue(new DhSectionPos(childDhSectionPos.sectionDetailLevel, childDhSectionPos.sectionX, childDhSectionPos.sectionZ)) != null;
				//LodUtil.assertTrue(valueAdded); // failed to add world gen task to quad tree, this means the quad tree was the wrong size

//				LOGGER.info("split feature "+sectionPos+" into "+childDhSectionPos+" "+(valueAdded ? "added" : "notAdded"));
			});
			
			// send the child futures to the future recipient, to notify them of the new tasks
			closestTask.future.complete(WorldGenResult.CreateSplit(childFutures));
			
			// return true so we attempt to generate again
			return true;
		}
	}
	private void startWorldGenTaskGroup(InProgressWorldGenTaskGroup inProgressTaskGroup)
	{
		byte taskDetailLevel = inProgressTaskGroup.group.dataDetail;
		DhLodPos taskPos = inProgressTaskGroup.group.pos;
		byte granularity = (byte) (taskPos.detailLevel - taskDetailLevel);
		LodUtil.assertTrue(granularity >= this.minGranularity && granularity <= this.maxGranularity);
		LodUtil.assertTrue(taskDetailLevel >= this.smallestDataDetail && taskDetailLevel <= this.largestDataDetail);
		
		DhChunkPos chunkPosMin = new DhChunkPos(taskPos.getCornerBlockPos());
		
		// check if this is a duplicate generation task
		if (this.alreadyGeneratedPosHashSet.containsKey(inProgressTaskGroup.group.pos))
		{
			// temporary solution to prevent generating the same section multiple times
			LOGGER.warn("Duplicate generation section " + taskPos + " with granularity [" + granularity + "] at " + chunkPosMin + ". Skipping...");
			
			//StackTraceElement[] stackTrace = this.alreadyGeneratedPosHashSet.get(inProgressTaskGroup.group.pos);
			
			// sending a success result is necessary to make sure the render sections are reloaded correctly 
			inProgressTaskGroup.group.worldGenTasks.forEach(worldGenTask -> worldGenTask.future.complete(WorldGenResult.CreateSuccess(new DhSectionPos(granularity, taskPos))));
			return;
		}
		this.alreadyGeneratedPosHashSet.put(inProgressTaskGroup.group.pos, Thread.currentThread().getStackTrace());
		this.alreadyGeneratedPosQueue.add(inProgressTaskGroup.group.pos);
		
		// remove extra tracked duplicate positions
		while (this.alreadyGeneratedPosQueue.size() > MAX_ALREADY_GENERATED_COUNT)
		{
			DhLodPos posToRemove = this.alreadyGeneratedPosQueue.poll();
			this.alreadyGeneratedPosHashSet.remove(posToRemove);
		}
		
		
		//LOGGER.info("Generating section "+taskPos+" with granularity "+granularity+" at "+chunkPosMin);
		
		this.numberOfTasksQueued++;
		inProgressTaskGroup.genFuture = this.startGenerationEvent(chunkPosMin, granularity, taskDetailLevel, inProgressTaskGroup.group::consumeChunkData);
		inProgressTaskGroup.genFuture.whenComplete((voidObj, exception) ->
		{
			this.numberOfTasksQueued--;
			if (exception != null)
			{
				// don't log the shutdown exceptions
				if (!LodUtil.isInterruptOrReject(exception))
				{
					LOGGER.error("Error generating data for section " + taskPos, exception);
				}
				
				inProgressTaskGroup.group.worldGenTasks.forEach(worldGenTask -> worldGenTask.future.complete(WorldGenResult.CreateFail()));
			}
			else
			{
				//LOGGER.info("Section generation at "+pos+" completed");
				inProgressTaskGroup.group.worldGenTasks.forEach(worldGenTask -> worldGenTask.future.complete(WorldGenResult.CreateSuccess(new DhSectionPos(granularity, taskPos))));
			}
			boolean worked = this.inProgressGenTasksByLodPos.remove(taskPos, inProgressTaskGroup);
			LodUtil.assertTrue(worked);
		});
	}
	/**
	 * The chunkPos is always aligned to the granularity.
	 * For example: if the granularity is 4 (chunk sized) with a data detail level of 0 (block sized),
	 * the chunkPos will be aligned to 16x16 blocks. <br> <br>
	 *
	 *
	 * <strong>Full Granularity definition (as of 2023-6-21): </strong> <br> <br>
	 *
	 * world gen actually supports (in theory) generating stuff with a data detail that's higher than the per-block. <br> <br>
	 *
	 * Granularity basically means, on a single generation task, how big such group should be, in terms of the data points it will make. <br> <br>
	 *
	 * For example, a granularity of 4 means the task will generate a 16 by 16 data points.
	 * Now, those data points might be per block, or per 4 by 4 blocks. Granularity doesn't say what detail those would be. <br> <br>
	 *
	 * Note: currently the core system sends data via the chunk sized container,
	 * which has the locked granularity of 4 (16 by 16 data columns), and thus generators should at least have min granularity of 4.
	 * (Gen chunk width in that context means how many 'chunk sized containers' it will fill up.
	 * Again, note that a 'chunk sized container' isn't necessary 16 by 16 Minecraft blocks wide.
	 * It only has to contain 16 by 16 columns of data points, in whatever data detail it might be in.)
	 * (So, with a generator whose only gen data detail is 0, it is the same as a MC chunk.)
	 */
	private CompletableFuture<Void> startGenerationEvent(
			DhChunkPos chunkPosMin,
			byte granularity, byte targetDataDetail,
			Consumer<ChunkSizedFullDataAccessor> chunkDataConsumer)
	{
		EDhApiDistantGeneratorMode generatorMode = Config.Client.Advanced.WorldGenerator.distantGeneratorMode.get();
		return this.generator.generateChunks(chunkPosMin.x, chunkPosMin.z, granularity, targetDataDetail, generatorMode, worldGeneratorThreadPool, (generatedObjectArray) ->
		{
			try
			{
				IChunkWrapper chunk = SingletonInjector.INSTANCE.get(IWrapperFactory.class).createChunkWrapper(generatedObjectArray);
				chunkDataConsumer.accept(LodDataBuilder.createChunkData(chunk));
			}
			catch (ClassCastException e)
			{
				DhLoggerBuilder.getLogger().error("World generator return type incorrect. Error: [" + e.getMessage() + "]. World generator disabled.", e);
				Config.Client.Advanced.WorldGenerator.enableDistantGeneration.set(false);
			}
		});
	}
	
	
	
	//==========================//
	// executor handler methods //
	//==========================//
	
	/**
	 * Creates a new executor. <br>
	 * Does nothing if an executor already exists.
	 */
	public static void setupWorldGenThreadPool()
	{
		// static setup
		if (configListener == null)
		{
			configListener = new ConfigChangeListener<>(Config.Client.Advanced.MultiThreading.numberOfWorldGenerationThreads, (threadCount) -> { setThreadPoolSize(threadCount); });
		}
		
		
		if (worldGeneratorThreadPool == null || worldGeneratorThreadPool.isTerminated())
		{
			LOGGER.info("Starting " + FullDataFileHandler.class.getSimpleName());
			setThreadPoolSize(Config.Client.Advanced.MultiThreading.numberOfWorldGenerationThreads.get());
		}
	}
	public static void setThreadPoolSize(int threadPoolSize)
	{
		if (worldGeneratorThreadPool != null)
		{
			// close the previous thread pool if one exists
			worldGeneratorThreadPool.shutdown();
		}
		
		worldGeneratorThreadPool = ThreadUtil.makeRateLimitedThreadPool(threadPoolSize, THREAD_FACTORY, Config.Client.Advanced.MultiThreading.runTimeRatioForWorldGenerationThreads);
		worldGeneratorThreadPool.setOnTerminatedEventHandler(WorldGenerationQueue::onWorldGenThreadPoolTerminated);
	}
	
	/**
	 * Stops any executing tasks and destroys the executor. <br>
	 * Does nothing if the executor isn't running.
	 */
	public static void shutdownWorldGenThreadPool()
	{
		if (worldGeneratorThreadPool != null)
		{
			LOGGER.info("Stopping " + FullDataFileHandler.class.getSimpleName());
			worldGeneratorThreadPool.shutdownNow();
		}
	}
	
	private static void onWorldGenThreadPoolTerminated()
	{
		LOGGER.debug("World generator thread pool terminated. Suggesting the JVM runs a garbage collection to clean up any loose world generation objects...");
		System.gc();
	}
	
	
	
	//=========//
	// getters //
	//=========//
	
	public int getWaitingTaskCount() { return this.waitingTasks.size(); }
	public int getInProgressTaskCount() { return this.inProgressGenTasksByLodPos.size(); }
	
	
	//==========//
	// shutdown //
	//==========//
	
	public CompletableFuture<Void> startClosing(boolean cancelCurrentGeneration, boolean alsoInterruptRunning)
	{
		LOGGER.info("Closing world gen queue");
		this.queueingThread.shutdownNow();
		
		
		// stop and remove any in progress tasks
		ArrayList<CompletableFuture<Void>> inProgressTasksCancelingFutures = new ArrayList<>(this.inProgressGenTasksByLodPos.size());
		this.inProgressGenTasksByLodPos.values().forEach(runningTaskGroup ->
		{
			CompletableFuture<Void> genFuture = runningTaskGroup.genFuture; // Do this to prevent it getting swapped out
			if (genFuture == null)
			{
				// genFuture's shouldn't be null, but sometimes they are...
				return;
			}
			
			
			if (cancelCurrentGeneration)
			{
				genFuture.cancel(alsoInterruptRunning);
			}
			
			inProgressTasksCancelingFutures.add(genFuture.handle((voidObj, exception) ->
			{
				if (exception instanceof CompletionException)
				{
					exception = exception.getCause();
				}
				
				if (!UncheckedInterruptedException.isInterrupt(exception) && !(exception instanceof CancellationException))
				{
					LOGGER.error("Error when terminating data generation for section " + runningTaskGroup.group.pos, exception);
				}
				
				return null;
			}));
		});
		this.generatorClosingFuture = CompletableFuture.allOf(inProgressTasksCancelingFutures.toArray(new CompletableFuture[0]));
		
		return this.generatorClosingFuture;
	}
	
	@Override
	public void close()
	{
		LOGGER.info("Closing " + WorldGenerationQueue.class.getSimpleName() + "...");
		
		if (this.generatorClosingFuture == null)
		{
			this.startClosing(true, true);
		}
		LodUtil.assertTrue(this.generatorClosingFuture != null);
		
		
		
		
		LOGGER.info("Awaiting world generator thread pool termination...");
		try
		{
			int waitTimeInSeconds = 3;
			if (!worldGeneratorThreadPool.awaitTermination(waitTimeInSeconds, TimeUnit.SECONDS))
			{
				LOGGER.warn("World generator thread pool shutdown didn't complete after [" + waitTimeInSeconds + "] seconds. Some world generator requests may still be running.");
			}
		}
		catch (InterruptedException e)
		{
			LOGGER.warn("World generator thread pool shutdown interrupted! Ignoring child threads...", e);
		}
		
		
		
		this.generator.close();
		
		
		
		try
		{
			this.generatorClosingFuture.cancel(true);
		}
		catch (Throwable e)
		{
			LOGGER.warn("Failed to close generation queue: ", e);
		}
		LOGGER.info("Finished closing " + WorldGenerationQueue.class.getSimpleName());
		DebugRenderer.unregister(this);
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	private boolean canGeneratePos(byte worldGenTaskGroupDetailLevel /*when in doubt use 0*/ , DhLodPos taskPos)
	{
		byte granularity = (byte) (taskPos.detailLevel - worldGenTaskGroupDetailLevel);
		return (granularity >= this.minGranularity && granularity <= this.maxGranularity);
	}
	
	/**
	 * Source: <a href="https://stackoverflow.com/questions/3706219/algorithm-for-iterating-over-an-outward-spiral-on-a-discrete-2d-grid-from-the-or">...</a>
	 * Description: Left-upper semi-diagonal (0-4-16-36-64) contains squared layer number (4 * layer^2).
	 * External if-statement defines layer and finds (pre-)result for position in corresponding row or
	 * column of left-upper semi-plane, and internal if-statement corrects result for mirror position.
	 */
	private static int gridSpiralIndexing(int X, int Y)
	{
		int index = 0;
		if (X * X >= Y * Y)
		{
			index = 4 * X * X - X - Y;
			if (X < Y)
				index = index - 2 * (X - Y);
		}
		else
		{
			index = 4 * Y * Y - X - Y;
			if (X < Y)
				index = index + 2 * (X - Y);
		}
		
		return index;
	}
	
	
	
	//=======//
	// debug //
	//=======//
	
	@Override
	public void debugRender(DebugRenderer r)
	{
		//if (true) return;
		waitingTasks.keySet().forEach((pos) -> {
			//DhLodPos pos = t.pos;
			r.renderBox(new DebugRenderer.Box(pos, -32f, 64f, 0.05f, Color.blue));
		});
		this.inProgressGenTasksByLodPos.forEach((pos, t) -> {
			r.renderBox(new DebugRenderer.Box(pos, -32f, 64f, 0.05f, Color.red));
		});
	}
	
}
