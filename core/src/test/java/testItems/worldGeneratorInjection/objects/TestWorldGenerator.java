package testItems.worldGeneratorInjection.objects;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector;
import com.seibel.distanthorizons.core.util.LodUtil;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Dummy test implementation object for world generator injection unit tests.
 *
 * @author James Seibel
 * @version 2022-12-5
 */
public class TestWorldGenerator implements IDhApiWorldGenerator
{
	public static final byte SMALLEST_DETAIL_LEVEL = 1;
	
	
	// testable methods //
	
	@Override
	public int getPriority() { return OverrideInjector.CORE_PRIORITY; }
	
	@Override
	public byte getSmallestDataDetailLevel() { return SMALLEST_DETAIL_LEVEL; }
	
	
	
	
	// not used when unit testing //
	
	//======================//
	// generator parameters //
	//======================//
	
	@Override
	public byte getLargestDataDetailLevel() { return LodUtil.BLOCK_DETAIL_LEVEL; }
	
	@Override
	public byte getMinGenerationGranularity() { return LodUtil.CHUNK_DETAIL_LEVEL; }
	
	@Override
	public byte getMaxGenerationGranularity() { return LodUtil.CHUNK_DETAIL_LEVEL + 2; }
	
	
	//===================//
	// generator methods //
	//===================//
	
	@Override
	public void close() { }
	
	@Override
	public boolean isBusy() { return false; }
	
	@Override
	public CompletableFuture<Void> generateChunks(int chunkPosMinX, int chunkPosMinZ, byte granularity, byte targetDataDetail, EDhApiDistantGeneratorMode maxGenerationStep, ExecutorService executorService, Consumer<Object[]> resultConsumer) { return null; }
	
	@Override
	public void preGeneratorTaskStart() { }
	
	
}

