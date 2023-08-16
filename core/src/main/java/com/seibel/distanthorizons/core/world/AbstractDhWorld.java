package com.seibel.distanthorizons.core.world;

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import org.apache.logging.log4j.Logger;

import java.io.Closeable;

/**
 * Represents an entire world (aka server) and
 * contains every level in that world.
 */
public abstract class AbstractDhWorld implements IDhWorld, Closeable
{
	protected static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	public final EWorldEnvironment environment;
	
	
	
	protected AbstractDhWorld(EWorldEnvironment environment) { this.environment = environment; }
	
	
	// remove the "throws IOException"
	@Override
	public abstract void close();
	
}
