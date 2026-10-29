package com.runetools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * How the plugin treats one HTTP answer from the RuneTools server. Kept free of OkHttp and RuneLite
 * types so the rules can be unit-tested on their own.
 *
 * <ul>
 *   <li>{@link Outcome#OK}: any 2xx in the shape the request expects, or 304 (an ETag poll's
 *       "unchanged"). A 2xx in the wrong shape (a captive portal, a proxy's page) is {@code FAILED}.</li>
 *   <li>{@link Outcome#KEY_REJECTED}: 401, or a JSON 403 whose {@code code} is {@code bad_key}. Counts
 *       toward stopping the loop, since a revoked key never heals on its own.</li>
 *   <li>{@link Outcome#NAME_TAKEN}: a 409 or JSON 403 whose {@code code} is {@code rsn_taken}. The key
 *       is fine; the server won't publish under a name linked to another RuneTools account.</li>
 *   <li>{@link Outcome#REFUSED}: any other 409 (a limit such as {@code limit_reached} or the in-game
 *       name cap). Sending again as the same character can't succeed, and the server's own words say
 *       why.</li>
 *   <li>{@link Outcome#PAYLOAD_REJECTED}: 400 or 413. The server can never accept this exact body.</li>
 *   <li>{@link Outcome#RATE_LIMITED}: 429.</li>
 *   <li>{@link Outcome#FAILED}: everything else, a 404, a 5xx, a 403 that isn't one of the above (a
 *       Cloudflare or proxy page) and a transport failure (status 0). An ordinary failure: that
 *       endpoint backs off and tries again.</li>
 * </ul>
 */
final class SyncResponse
{
	/** The {@code code} the server puts on a refusal to publish under somebody else's in-game name. */
	static final String RSN_TAKEN = "rsn_taken";
	/** The {@code code} on a 403 that means the key itself is no good. */
	static final String BAD_KEY = "bad_key";

	/** What a 2xx answer must look like before the request counts as done. */
	enum Shape
	{
		/** Anything (an ETag poll's empty 304 never reaches the check). */
		ANY,
		/** A JSON object with {@code "ok": true}: state, Combat Achievements, Collection Log, achievements. */
		OK_TRUE,
		/** A JSON object whose {@code received} equals the number of trades sent. */
		TRADES,
		/** A JSON object with a {@code setups} array. */
		SETUPS,
		/** A JSON object with a {@code layouts} array. */
		LAYOUTS,
		/** A JSON object carrying the setup's numeric {@code id}. */
		SETUP,
		/** A JSON object carrying {@code deleted}. */
		DELETED,
		/** Any JSON object. */
		OBJECT
	}

	enum Outcome
	{
		OK,
		KEY_REJECTED,
		NAME_TAKEN,
		REFUSED,
		PAYLOAD_REJECTED,
		RATE_LIMITED,
		FAILED
	}

	private SyncResponse()
	{
	}

	/**
	 * Classify a status. {@code errorCode} is the {@code code} field of the JSON error body, or null
	 * (also null when the body wasn't JSON at all).
	 */
	static Outcome classify(int status, String errorCode)
	{
		if ((status >= 200 && status < 300) || status == 304)
		{
			return Outcome.OK;
		}
		switch (status)
		{
			case 401:
				return Outcome.KEY_REJECTED;
			case 403:
				if (BAD_KEY.equals(errorCode))
				{
					return Outcome.KEY_REJECTED;
				}
				return RSN_TAKEN.equals(errorCode) ? Outcome.NAME_TAKEN : Outcome.FAILED;
			case 409:
				return RSN_TAKEN.equals(errorCode) ? Outcome.NAME_TAKEN : Outcome.REFUSED;
			case 400:
			case 413:
				return Outcome.PAYLOAD_REJECTED;
			case 429:
				return Outcome.RATE_LIMITED;
			default:
				return Outcome.FAILED;
		}
	}

	/** Classify an answer whose body has been read: a 2xx counts only in the expected shape. */
	static Outcome classify(int status, String body, Shape shape, int sent)
	{
		if (status >= 200 && status < 300 && !confirms(shape, body, sent))
		{
			return Outcome.FAILED;
		}
		return classify(status, status >= 400 && status < 500 ? errorCode(body) : null);
	}

	/** Whether a 4xx body is worth reading for its {@code code} and {@code error}. */
	static boolean readsErrorBody(int status)
	{
		return status >= 400 && status < 500;
	}

	/**
	 * What counts toward giving up on one payload: a 500, the server failing on what it was sent. A
	 * 502, 503, 504 or a Cloudflare 52x is the site or the way to it being down, which says nothing
	 * about the payload, so an outage never gives anything up.
	 */
	static boolean serverError(int status)
	{
		return status == 500;
	}

	/** Whether a 2xx body is the shape {@code shape} promises. Never throws. */
	static boolean confirms(Shape shape, String body, int sent)
	{
		if (shape == Shape.ANY)
		{
			return true;
		}
		JsonObject o = object(body);
		if (o == null)
		{
			return false;
		}
		switch (shape)
		{
			case OK_TRUE:
				return isTrue(o.get("ok"));
			case TRADES:
			{
				JsonElement e = o.get("received");
				return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && e.getAsInt() == sent;
			}
			case SETUPS:
				return o.get("setups") != null && o.get("setups").isJsonArray();
			case LAYOUTS:
				return o.get("layouts") != null && o.get("layouts").isJsonArray();
			case SETUP:
			{
				JsonElement e = o.get("id");
				return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && e.getAsLong() > 0;
			}
			case DELETED:
				return o.has("deleted");
			default:
				return true;
		}
	}

	/** The {@code code} field of a JSON error body, or null for anything else. Never throws. */
	static String errorCode(String body)
	{
		return field(body, "code", 64);
	}

	/**
	 * The {@code error} text of a JSON error body, cleaned and capped for the panel, or null when the
	 * body has none (an HTML page, an empty body). Never throws.
	 */
	static String errorText(String body)
	{
		String t = field(body, "error", 200);
		return t != null && !t.trim().isEmpty() ? t.trim() : null;
	}

	/**
	 * What to tell the player about a failed request they made: the server's own {@code error} text
	 * when it sent one, else {@code fallback} with a short reason. {@code status} 0 is no answer.
	 */
	static String describe(int status, String body, String fallback)
	{
		String text = status >= 400 ? errorText(body) : null;
		if (text != null)
		{
			return text;
		}
		if (status == 401 || (status == 403 && BAD_KEY.equals(errorCode(body))))
		{
			return "Key rejected. Reconnect this character.";
		}
		if (status == 429)
		{
			return "Too many requests. Try again in a minute.";
		}
		if (status == 0)
		{
			return fallback + " The site didn't answer.";
		}
		if (status >= 200 && status < 300)
		{
			return fallback + " Unexpected answer from the site.";
		}
		return fallback + " (HTTP " + status + ")";
	}

	private static String field(String body, String key, int max)
	{
		JsonObject o = object(body);
		if (o == null)
		{
			return null;
		}
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive())
		{
			return null;
		}
		JsonPrimitive p = e.getAsJsonPrimitive();
		return p.isString() || p.isNumber() ? RemoteConfig.clean(p.getAsString(), max) : null;
	}

	private static JsonObject object(String body)
	{
		if (body == null || body.trim().isEmpty())
		{
			return null;
		}
		try
		{
			JsonElement root = new JsonParser().parse(body);
			return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
		}
		catch (RuntimeException ex)
		{
			return null;   // an HTML error page from a proxy, a truncated body, ...
		}
	}

	private static boolean isTrue(JsonElement e)
	{
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
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
