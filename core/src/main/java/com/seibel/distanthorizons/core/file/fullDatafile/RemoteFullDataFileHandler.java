package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.network.ChildNetworkEventSource;
import com.seibel.distanthorizons.core.network.NetworkClient;
import com.seibel.distanthorizons.core.network.future.NetworkRequestTracker;
import com.seibel.distanthorizons.core.network.messages.ChunkRequestMessage;
import com.seibel.distanthorizons.core.network.messages.ChunkResponseMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;

import java.util.concurrent.CompletableFuture;

public class RemoteFullDataFileHandler extends FullDataFileHandler
{
    private final NetworkClient networkClient;
    private final NetworkRequestTracker<ChunkResponseMessage, DhSectionPos> chunkRequestTracker;
    
    public RemoteFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure, NetworkClient networkClient) {
        super(level, saveStructure);
        this.networkClient = networkClient;
        this.chunkRequestTracker = new NetworkRequestTracker<>(networkClient, ChunkResponseMessage.class);
    }

    @Override
    public CompletableFuture<IFullDataSource> read(DhSectionPos pos) {
        // TODO read and force update somehow instead ????
        return super.read(pos).thenCompose((fullDataSource) -> {
            if (fullDataSource != null) {
                return CompletableFuture.completedFuture(fullDataSource);
            }

            CompletableFuture<ChunkResponseMessage> responseFuture = chunkRequestTracker.sendRequest(networkClient.getChannel(), new ChunkRequestMessage());
            
        });
    }

    @Override
    public void close() {
        super.close();
        chunkRequestTracker.close();
    }
}
