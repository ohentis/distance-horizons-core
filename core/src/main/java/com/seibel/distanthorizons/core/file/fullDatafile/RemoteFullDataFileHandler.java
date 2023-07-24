package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.network.NetworkClient;
import com.seibel.distanthorizons.core.network.messages.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

public class RemoteFullDataFileHandler extends FullDataFileHandler
{
    protected static final Logger LOGGER = DhLoggerBuilder.getLogger();
    
    private final NetworkClient networkClient;
    
    private final F3Screen.NestedMessage f3Message;
    private int finishedRequests = 0;
    private int totalRequests = 0;
    private int failedRequests = 0;
    
    
    public RemoteFullDataFileHandler(IDhLevel level, AbstractSaveStructure saveStructure, NetworkClient networkClient) {
        super(level, saveStructure);
        this.networkClient = networkClient;
        this.f3Message = new F3Screen.NestedMessage(this::f3Log);
    }

    @Override
    public CompletableFuture<IFullDataSource> read(DhSectionPos pos) {
        FullDataMetaFile metaFile = this.getLoadOrMakeFile(pos, false);
        if (metaFile != null)
        {
            return super.read(pos).thenCompose(fullDataSource -> requestFromServer(pos, fullDataSource));
        }
        else
        {
            return requestFromServer(pos, null).thenCompose(fullDataSource -> fullDataSource != null
                    ? CompletableFuture.completedFuture(fullDataSource)
                    : super.read(pos));
        }
    }
    
    @NotNull
    private CompletableFuture<IFullDataSource> requestFromServer(DhSectionPos pos, IFullDataSource fullDataSource)
    {
        totalRequests++;
        return networkClient.<FullDataSourceResponseMessage>sendRequest(new FullDataSourceRequestMessage(pos))
                .handle((response, throwable) -> {
                    try
                    {
                        finishedRequests++;
                        if (throwable != null)
                            throw throwable;
                        
                        LOGGER.info("FullDataSourceResponseMessage " + pos);
                        FullDataMetaFile metaFile = this.getLoadOrMakeFile(pos, true);
                        return response.getFullDataSource(metaFile, level);
                    }
                    catch (Throwable e)
                    {
                        failedRequests++;
                        LOGGER.error("Error while fetching full data source", e);
                        return fullDataSource;
                    }
                });
    }
    
    private String[] f3Log()
    {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("Remote Full Data File Handler ["+this.level.getLevelWrapper().getDimensionType().getDimensionName()+"]");
        lines.add("  Requests: "+this.finishedRequests +" / "+this.totalRequests +" (failed: "+ this.failedRequests+")");
        return lines.toArray(new String[0]);
    }

    @Override
    public void close() {
        f3Message.close();
        super.close();
    }
}
