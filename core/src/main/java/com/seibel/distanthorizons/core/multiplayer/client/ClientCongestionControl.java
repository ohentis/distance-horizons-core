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
	
	private double currentRate = 50000;
	private long lastAdjustTime = System.currentTimeMillis();
	
	private final IntSupplier currentMaxRateSupplier;
	private final BooleanSupplier hasIncompleteBuffersSupplier;
	private final IntConsumer rateUpdateConsumer;
	
	
	public ClientCongestionControl(
			IntSupplier currentMaxRateSupplier,
			BooleanSupplier hasIncompleteBuffersSupplier,
			IntConsumer rateUpdateConsumer
	)
	{
		this.currentMaxRateSupplier = currentMaxRateSupplier;
		this.hasIncompleteBuffersSupplier = hasIncompleteBuffersSupplier;
		this.rateUpdateConsumer = rateUpdateConsumer;
	}
	
	
	public void onPayloadReceived(FullDataSplitMessage message)
	{
		this.bytesReceived.addAndGet(message.buffer.readableBytes());
		
		long now = System.currentTimeMillis();
		if (now - this.lastAdjustTime >= INTERVAL_MS)
		{
			this.adjustRate(now);
		}
	}
	
	private void adjustRate(long now)
	{
		double throughput = this.bytesReceived.getAndSet(0);
		
		if (throughput != 0 && throughput >= this.lastIntervalThroughput)
		{
			this.currentRate = Math.min(this.currentRate, this.currentMaxRateSupplier.getAsInt() * 1000) + ADDITIVE_INCREASE;
		}
		else if (this.hasIncompleteBuffersSupplier.getAsBoolean())
		{
			this.currentRate *= MULTIPLICATIVE_DECREASE;
			throughput *= MULTIPLICATIVE_DECREASE;
			
			if (this.currentRate < 1)
			{
				this.currentRate = 1;
			}
		}
		
		this.lastIntervalThroughput = throughput;
		this.lastAdjustTime = now;
		
		this.rateUpdateConsumer.accept((int) this.currentRate / 1000);
	}
	
	public double getCurrentRate()
	{
		return this.currentRate;
	}
	
}
