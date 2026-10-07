package com.runetools;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * How the background sync loop treats one HTTP answer from the RuneTools server. Kept free of OkHttp
 * and RuneLite types so the rules can be unit-tested on their own.
 *
 * <ul>
 *   <li>{@link Outcome#OK}: any 2xx, or 304 (how the ETag polls say "unchanged").</li>
 *   <li>{@link Outcome#IGNORED}: 404. Several endpoints legitimately 404 until the server ships them.</li>
 *   <li>{@link Outcome#KEY_REJECTED}: 401, or a 403 that is not the name conflict below. Counts toward
 *       stopping the loop, since a revoked key never heals on its own.</li>
 *   <li>{@link Outcome#NAME_TAKEN}: 409, or a 403 whose body carries {@code "code": "rsn_taken"}. The
 *       key is fine; this endpoint just won't publish under a name linked to another RuneTools account,
 *       so sending again as the same character can't succeed.</li>
 *   <li>{@link Outcome#PAYLOAD_REJECTED}: 400 or 413. The server can never accept this exact body.</li>
 *   <li>{@link Outcome#RATE_LIMITED}: 429.</li>
 *   <li>{@link Outcome#FAILED}: everything else, including 5xx and a transport failure (status 0).</li>
 * </ul>
 */
final class SyncResponse
{
	/** The {@code code} the server puts on a refusal to publish under somebody else's in-game name. */
	static final String RSN_TAKEN = "rsn_taken";

	enum Outcome
	{
		OK,
		IGNORED,
		KEY_REJECTED,
		NAME_TAKEN,
		PAYLOAD_REJECTED,
		RATE_LIMITED,
		FAILED
	}

	private SyncResponse()
	{
	}

	/**
	 * Classify a status. {@code errorCode} is the {@code code} field of the JSON error body, or null;
	 * it only matters for a 403 (see {@link #needsErrorCode}).
	 */
	static Outcome classify(int status, String errorCode)
	{
		if ((status >= 200 && status < 300) || status == 304)
		{
			return Outcome.OK;
		}
		switch (status)
		{
			case 404:
				return Outcome.IGNORED;
			case 401:
				return Outcome.KEY_REJECTED;
			case 403:
				return RSN_TAKEN.equals(errorCode) ? Outcome.NAME_TAKEN : Outcome.KEY_REJECTED;
			case 409:
				return Outcome.NAME_TAKEN;
			case 400:
			case 413:
				return Outcome.PAYLOAD_REJECTED;
			case 429:
				return Outcome.RATE_LIMITED;
			default:
				return Outcome.FAILED;
		}
	}

	/** Whether {@link #classify} needs the body's error code for this status (only 403 is ambiguous). */
	static boolean needsErrorCode(int status)
	{
		return status == 403;
	}

	/** The {@code code} field of a JSON error body, or null for anything else. Never throws. */
	static String errorCode(String body)
	{
		if (body == null || body.trim().isEmpty())
		{
			return null;
		}
		try
		{
			JsonElement root = new JsonParser().parse(body);
			if (root == null || !root.isJsonObject())
			{
				return null;
			}
			JsonElement code = root.getAsJsonObject().get("code");
			return code != null && code.isJsonPrimitive() ? code.getAsString() : null;
		}
		catch (RuntimeException ex)
		{
			return null;   // an HTML error page from a proxy, a truncated body, ...
		}
	}

	/**
	 * How long a {@code Retry-After} header asks us to wait, in milliseconds: either delta-seconds or
	 * an HTTP-date. Returns -1 when the header is absent or unreadable, 0 for a date already past, and
	 * never more than {@code maxMillis}, so a garbled or hostile value can't silence the plugin for good.
	 */
	static long retryAfterMillis(String header, long nowMillis, long maxMillis)
	{
		if (header == null)
		{
			return -1;
		}
		String v = header.trim();
		if (v.isEmpty())
		{
			return -1;
		}
		long millis;
		if (v.chars().allMatch(c -> c >= '0' && c <= '9'))
		{
			// More than 12 digits is past any cap we'd honour and could overflow the multiply.
			millis = v.length() > 12 ? maxMillis : Long.parseLong(v) * 1000L;
		}
		else
		{
			try
			{
				millis = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
					- nowMillis;
			}
			catch (DateTimeParseException ex)
			{
				return -1;
			}
		}
		return Math.max(0L, Math.min(millis, maxMillis));
	}
}
