package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.interfaces.IFullDataSource;
import com.seibel.distanthorizons.core.file.structure.AbstractSaveStructure;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.NetworkClient;
import com.seibel.distanthorizons.core.network.messages.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import org.apache.logging.log4j.Logger;

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
        return super.read(pos).thenCompose(fullDataSource -> {
            if (fullDataSource == null)
                return null;
            
            if (!fullDataSource.isEmpty())
                return CompletableFuture.completedFuture(fullDataSource);
            
            FullDataMetaFile metaFile = this.getLoadOrMakeFile(pos, true);
            return networkClient.<FullDataSourceResponseMessage>sendRequest(new FullDataSourceRequestMessage(pos))
                    .handle((response, throwable) -> {
                        try
                        {
                            if (throwable != null)
                                throw throwable;
                            
                            LOGGER.info("FullDataSourceResponseMessage " + pos);
                            return response.getFullDataSource(metaFile, pos, level);
                        }
                        catch (Throwable e)
                        {
                            LOGGER.error(e);
                            return null;
                        }
                    });
        });
    }

    @Override
    public void close() {
        super.close();
    }
}
