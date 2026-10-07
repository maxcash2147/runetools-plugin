package com.runetools;

import com.runetools.SyncResponse.Outcome;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.Assert;
import org.junit.Test;

public class SyncResponseTest
{
	private static final long MAX = 60L * 60L * 1000L;

	@Test
	public void successAndNotModifiedAreOk()
	{
		Assert.assertEquals(Outcome.OK, SyncResponse.classify(200, null));
		Assert.assertEquals(Outcome.OK, SyncResponse.classify(204, null));
		Assert.assertEquals(Outcome.OK, SyncResponse.classify(304, null));
	}

	@Test
	public void notFoundIsIgnored()
	{
		Assert.assertEquals(Outcome.IGNORED, SyncResponse.classify(404, null));
	}

	@Test
	public void onlyUnauthorizedAndPlainForbiddenRejectTheKey()
	{
		Assert.assertEquals(Outcome.KEY_REJECTED, SyncResponse.classify(401, null));
		Assert.assertEquals(Outcome.KEY_REJECTED, SyncResponse.classify(403, null));
		Assert.assertEquals(Outcome.KEY_REJECTED, SyncResponse.classify(403, "something_else"));
	}

	@Test
	public void nameConflictIsNotAKeyRejection()
	{
		Assert.assertEquals(Outcome.NAME_TAKEN, SyncResponse.classify(409, null));
		Assert.assertEquals(Outcome.NAME_TAKEN, SyncResponse.classify(409, SyncResponse.RSN_TAKEN));
		Assert.assertEquals(Outcome.NAME_TAKEN, SyncResponse.classify(403, SyncResponse.RSN_TAKEN));
	}

	@Test
	public void badRequestAndTooLargeRejectThePayload()
	{
		Assert.assertEquals(Outcome.PAYLOAD_REJECTED, SyncResponse.classify(400, null));
		Assert.assertEquals(Outcome.PAYLOAD_REJECTED, SyncResponse.classify(413, null));
	}

	@Test
	public void rateLimitAndEverythingElse()
	{
		Assert.assertEquals(Outcome.RATE_LIMITED, SyncResponse.classify(429, null));
		Assert.assertEquals(Outcome.FAILED, SyncResponse.classify(0, null));     // transport failure
		Assert.assertEquals(Outcome.FAILED, SyncResponse.classify(500, null));
		Assert.assertEquals(Outcome.FAILED, SyncResponse.classify(503, null));
		Assert.assertEquals(Outcome.FAILED, SyncResponse.classify(405, null));
	}

	@Test
	public void onlyForbiddenNeedsTheBody()
	{
		Assert.assertTrue(SyncResponse.needsErrorCode(403));
		Assert.assertFalse(SyncResponse.needsErrorCode(409));
		Assert.assertFalse(SyncResponse.needsErrorCode(401));
	}

	@Test
	public void errorCodeIsReadFromTheServersBody()
	{
		Assert.assertEquals("rsn_taken", SyncResponse.errorCode(
			"{\"error\": \"That in-game name belongs to another RuneTools account.\", \"code\": \"rsn_taken\"}"));
		Assert.assertNull(SyncResponse.errorCode("{\"error\": \"Sign in to use this feature.\"}"));
	}

	@Test
	public void errorCodeNeverThrowsOnJunk()
	{
		Assert.assertNull(SyncResponse.errorCode(null));
		Assert.assertNull(SyncResponse.errorCode(""));
		Assert.assertNull(SyncResponse.errorCode("<html><body>403 Forbidden</body></html>"));
		Assert.assertNull(SyncResponse.errorCode("[1, 2]"));
		Assert.assertNull(SyncResponse.errorCode("{\"code\": {\"nested\": true}}"));
		Assert.assertNull(SyncResponse.errorCode("{\"code\": "));
	}

	@Test
	public void retryAfterSeconds()
	{
		Assert.assertEquals(60_000L, SyncResponse.retryAfterMillis("60", 0L, MAX));
		Assert.assertEquals(5_000L, SyncResponse.retryAfterMillis(" 5 ", 0L, MAX));
		Assert.assertEquals(0L, SyncResponse.retryAfterMillis("0", 0L, MAX));
	}

	@Test
	public void retryAfterIsCapped()
	{
		Assert.assertEquals(MAX, SyncResponse.retryAfterMillis("86400", 0L, MAX));
		Assert.assertEquals(MAX, SyncResponse.retryAfterMillis("99999999999999999999999", 0L, MAX));
	}

	@Test
	public void retryAfterHttpDate()
	{
		long now = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli();
		String in90s = DateTimeFormatter.RFC_1123_DATE_TIME.format(
			Instant.ofEpochMilli(now + 90_000L).atZone(ZoneOffset.UTC));
		Assert.assertEquals(90_000L, SyncResponse.retryAfterMillis(in90s, now, MAX));
		Assert.assertEquals(0L, SyncResponse.retryAfterMillis("Wed, 21 Oct 2015 07:28:00 GMT", now, MAX));
	}

	@Test
	public void retryAfterAbsentOrUnreadable()
	{
		Assert.assertEquals(-1L, SyncResponse.retryAfterMillis(null, 0L, MAX));
		Assert.assertEquals(-1L, SyncResponse.retryAfterMillis("", 0L, MAX));
		Assert.assertEquals(-1L, SyncResponse.retryAfterMillis("soon", 0L, MAX));
		Assert.assertEquals(-1L, SyncResponse.retryAfterMillis("-5", 0L, MAX));
	}
}
