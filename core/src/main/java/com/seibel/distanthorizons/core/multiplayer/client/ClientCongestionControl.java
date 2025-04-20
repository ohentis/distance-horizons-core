package com.seibel.distanthorizons.core.multiplayer.client;

import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSplitMessage;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public class ClientCongestionControl
{
	private static final double ADDITIVE_INCREASE = 50000;
	private static final double MULTIPLICATIVE_DECREASE = 0.75;
	private static final long INTERVAL_MS = 1000;
	
	private final AtomicLong bytesReceived = new AtomicLong(0);
	private volatile double lastIntervalThroughput = 0;
	
	private double desiredRate = 50000;
	private long lastAdjustTime = System.currentTimeMillis();
	
	private final IntSupplier currentRateSupplier;
	private final Runnable rateUpdateHandler;
	
	
	public ClientCongestionControl(
			IntSupplier currentRateSupplier,
			Runnable rateUpdateHandler
	)
	{
		this.currentRateSupplier = currentRateSupplier;
		this.rateUpdateHandler = rateUpdateHandler;
	}
	
	
	public void onPayloadReceived(FullDataSplitMessage message)
	{
		long now = System.currentTimeMillis();
		if (now - this.lastAdjustTime >= INTERVAL_MS)
		{
			this.adjustRate(now);
		}
		
		this.bytesReceived.addAndGet(message.buffer.readableBytes());
	}
	
	private void adjustRate(long now)
	{
		this.desiredRate = this.currentRateSupplier.getAsInt() * 1000;
		double throughput = this.bytesReceived.getAndSet(0);
		
		if (throughput != 0 && throughput >= this.lastIntervalThroughput)
		{
			this.desiredRate += ADDITIVE_INCREASE;
		}
		else
		{
			this.desiredRate *= MULTIPLICATIVE_DECREASE;
			throughput *= MULTIPLICATIVE_DECREASE;
			
			if (this.desiredRate < 1)
			{
				this.desiredRate = 1;
			}
		}
		
		this.lastIntervalThroughput = throughput;
		this.lastAdjustTime = now;
		
		this.rateUpdateHandler.run();
	}
	
	public int getDesiredRate()
	{
		return (int) (this.desiredRate / 1000);
	}
	
}
