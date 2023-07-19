package com.seibel.distanthorizons.core.file.fullDatafile;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.network.ChildNetworkEventSource;
import com.seibel.distanthorizons.core.network.NetworkClient;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import io.netty.channel.ChannelHandlerContext;

import java.util.concurrent.CompletableFuture;

public class RemoteFullDataFileHandler extends FullDataFileHandler
{
    private final Multimap<ChannelHandlerContext, ChunkRequest> chunkRequests = HashMultimap.create();

    public RemoteFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure, ChildNetworkEventSource<NetworkClient> eventSource) {
        super(level, saveStructure);
        this.registerNetworkHandlers(eventSource);
    }

    private void registerNetworkHandlers(ChildNetworkEventSource<NetworkClient> eventSource) {
        //eventSource.registerHandler();
    }

    @Override
    public CompletableFuture<IFullDataSource> read(DhSectionPos pos) {
        // TODO read and force update somehow instead ????
        return super.read(pos).handle((fullDataSource, throwable) -> {
            if (fullDataSource == null) {

            }

            return fullDataSource;
        });
    }

    @Override
    public void close() {
        super.close();


    }

    private static class ChunkRequest {

    }
}
