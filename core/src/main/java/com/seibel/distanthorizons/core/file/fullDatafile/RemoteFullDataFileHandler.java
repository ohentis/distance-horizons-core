package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.ChildNetworkEventSource;
import com.seibel.distanthorizons.core.network.NetworkClient;
import com.seibel.distanthorizons.core.network.messages.ChunkRequestMessage;
import com.seibel.distanthorizons.core.network.messages.ChunkResponseMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import org.apache.logging.log4j.Logger;

import java.nio.file.FileAlreadyExistsException;
import java.util.concurrent.CompletableFuture;

public class RemoteFullDataFileHandler extends FullDataFileHandler
{
    protected static final Logger LOGGER = DhLoggerBuilder.getLogger();
    
    private final NetworkClient networkClient;
    
    public RemoteFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure, NetworkClient networkClient) {
        super(level, saveStructure);
        this.networkClient = networkClient;
    }

    @Override
    public CompletableFuture<IFullDataSource> read(DhSectionPos pos) {
        // TODO: LOD data file updating is probably incomplete
        return super.read(pos).thenCompose((fullDataSource) -> {
            CompletableFuture<ChunkResponseMessage> responseFuture = networkClient.<ChunkResponseMessage>sendRequest(new ChunkRequestMessage(pos))
                    .exceptionally(throwable -> {
                        LOGGER.error(throwable);
                        return null;
                    });
            responseFuture.thenAccept(response -> LOGGER.info("ChunkResponseMessage "+pos));
            
            FullDataMetaFile metaFile = this.getLoadOrMakeFile(pos, true);
            return onDataFileUpdate(fullDataSource, metaFile, iFullDataSource -> {}, iFullDataSource -> true);
        });
    }

    @Override
    public void close() {
        super.close();
    }
}
