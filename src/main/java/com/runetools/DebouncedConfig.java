package com.runetools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * One place that writes the plugin's large config values (the setups cache, the unsynced-trade
 * queue). Writes are collected and flushed together a moment later on one executor thread, so a
 * burst of changes (a fill every tick, a pull after every setup edit) becomes one write of each key
 * instead of a rewrite of a big blob per change, and no OkHttp thread writes config itself.
 *
 * <p>Reads go through {@link #get} so a value that hasn't been flushed yet is still the one read.
 * {@link #flush} writes everything now (the plugin calls it when it is switched off). Thread-safe.
 */
final class DebouncedConfig
{
	private static final Object UNSET = new Object();

	private final BiConsumer<String, String> write;    // value null = unset the key
	private final Function<String, String> read;
	private final ScheduledExecutorService executor;   // null = write at once (tests)
	private final long delayMs;

	private final Map<String, Object> dirty = new LinkedHashMap<>();
	private final Map<String, Object> writing = new LinkedHashMap<>();   // taken by a flush, not yet written
	private ScheduledFuture<?> scheduled;
	private final Object flushLock = new Object();   // flushes run one at a time, so writes land in order

	DebouncedConfig(BiConsumer<String, String> write, Function<String, String> read,
		ScheduledExecutorService executor, long delayMs)
	{
		this.write = write;
		this.read = read;
		this.executor = executor;
		this.delayMs = delayMs;
	}

	/** Set {@code key} (null unsets it), written within {@code delayMs}. */
	void set(String key, String value)
	{
		synchronized (this)
		{
			dirty.put(key, value != null ? value : UNSET);
			if (executor != null)
			{
				if (scheduled == null || scheduled.isDone())
				{
					scheduled = executor.schedule(this::flush, delayMs, TimeUnit.MILLISECONDS);
				}
				return;
			}
		}
		flush();
	}

	/** The value of {@code key}, including one still waiting to be written. */
	String get(String key)
	{
		synchronized (this)
		{
			Object v = dirty.containsKey(key) ? dirty.get(key) : writing.get(key);
			if (v != null)
			{
				return v == UNSET ? null : (String) v;
			}
		}
		return read.apply(key);
	}

	/** Write everything waiting, now. */
	void flush()
	{
		synchronized (flushLock)
		{
			Map<String, Object> batch;
			synchronized (this)
			{
				if (dirty.isEmpty())
				{
					return;
				}
				batch = new LinkedHashMap<>(dirty);
				dirty.clear();
				writing.putAll(batch);
				scheduled = null;
			}
			for (Map.Entry<String, Object> en : batch.entrySet())
			{
				try
				{
					write.accept(en.getKey(), en.getValue() == UNSET ? null : (String) en.getValue());
				}
				catch (RuntimeException ignored)
				{
					// one bad write must not stop the rest
				}
				synchronized (this)
				{
					writing.remove(en.getKey(), en.getValue());
				}
			}
		}
	}
}
