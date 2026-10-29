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
	public void notFoundIsAnOrdinaryFailure()
	{
		// It backs that endpoint off like any failure (it used to be ignored and retried every sweep).
		Assert.assertEquals(Outcome.FAILED, SyncResponse.classify(404, null));
	}

	@Test
	public void onlyUnauthorizedAndABadKeyCodeRejectTheKey()
	{
		Assert.assertEquals(Outcome.KEY_REJECTED, SyncResponse.classify(401, null));
		Assert.assertEquals(Outcome.KEY_REJECTED, SyncResponse.classify(403, SyncResponse.BAD_KEY));
	}

	@Test
	public void aForbiddenPageThatIsntOursIsAnOrdinaryFailure()
	{
		// Cloudflare or a proxy answering 403 with its own page must never halt sync as a "revoked key".
		Assert.assertEquals(Outcome.FAILED, SyncResponse.classify(403, null));
		Assert.assertEquals(Outcome.FAILED, SyncResponse.classify(403, "something_else"));
		Assert.assertEquals(Outcome.FAILED, SyncResponse.classify(403,
			"<html><body>Attention Required! | Cloudflare</body></html>", SyncResponse.Shape.ANY, 0));
	}

	@Test
	public void nameConflictIsNotAKeyRejection()
	{
		Assert.assertEquals(Outcome.NAME_TAKEN, SyncResponse.classify(409, SyncResponse.RSN_TAKEN));
		Assert.assertEquals(Outcome.NAME_TAKEN, SyncResponse.classify(403, SyncResponse.RSN_TAKEN));
	}

	@Test
	public void otherConflictsAreRefusalsNotNameConflicts()
	{
		Assert.assertEquals(Outcome.REFUSED, SyncResponse.classify(409, null));
		Assert.assertEquals(Outcome.REFUSED, SyncResponse.classify(409, "limit_reached"));
		String cap = "{\"error\": \"This account already syncs 20 in-game names.\"}";
		Assert.assertEquals(Outcome.REFUSED, SyncResponse.classify(409, cap, SyncResponse.Shape.OK_TRUE, 0));
		Assert.assertEquals("This account already syncs 20 in-game names.", SyncResponse.errorText(cap));
	}

	@Test
	public void aSuccessInTheWrongShapeIsAFailure()
	{
		Assert.assertEquals(Outcome.FAILED, SyncResponse.classify(200, "<html>Captive portal</html>",
			SyncResponse.Shape.OK_TRUE, 0));
		Assert.assertEquals(Outcome.FAILED, SyncResponse.classify(200, "{\"ok\": false}", SyncResponse.Shape.OK_TRUE, 0));
		Assert.assertEquals(Outcome.OK, SyncResponse.classify(200, "{\"ok\": true, \"rsn\": \"Zezima\"}",
			SyncResponse.Shape.OK_TRUE, 0));
		// A setups answer without its array must never be taken as "no setups" (it would wipe the list).
		Assert.assertFalse(SyncResponse.confirms(SyncResponse.Shape.SETUPS, "{\"server_ts\": 1}", 0));
		Assert.assertFalse(SyncResponse.confirms(SyncResponse.Shape.SETUPS, "{\"setups\": {}}", 0));
		Assert.assertTrue(SyncResponse.confirms(SyncResponse.Shape.SETUPS, "{\"setups\": []}", 0));
		Assert.assertFalse(SyncResponse.confirms(SyncResponse.Shape.LAYOUTS, "[]", 0));
		Assert.assertTrue(SyncResponse.confirms(SyncResponse.Shape.LAYOUTS, "{\"layouts\": []}", 0));
		Assert.assertTrue(SyncResponse.confirms(SyncResponse.Shape.SETUP, "{\"id\": 12, \"name\": \"x\"}", 0));
		Assert.assertFalse(SyncResponse.confirms(SyncResponse.Shape.SETUP, "{\"name\": \"x\"}", 0));
	}

	@Test
	public void tradesCountOnlyWhenTheServerConfirmsEveryOne()
	{
		String body = "{\"imported\": 2, \"received\": 3, \"total\": 10}";
		Assert.assertTrue(SyncResponse.confirms(SyncResponse.Shape.TRADES, body, 3));
		Assert.assertFalse(SyncResponse.confirms(SyncResponse.Shape.TRADES, body, 4));
		Assert.assertFalse(SyncResponse.confirms(SyncResponse.Shape.TRADES, "{\"ok\": true}", 3));
		Assert.assertEquals(Outcome.FAILED, SyncResponse.classify(200, "", SyncResponse.Shape.TRADES, 3));
	}

	@Test
	public void describeUsesTheServersWordsAndNoStatusCodeWhenItHasThem()
	{
		String limit = "{\"error\": \"You can keep up to 300 gear setups. Delete one to add another.\", "
			+ "\"code\": \"limit_reached\"}";
		Assert.assertEquals("You can keep up to 300 gear setups. Delete one to add another.",
			SyncResponse.describe(409, limit, "Couldn't save."));
		Assert.assertEquals("Couldn't save. (HTTP 503)", SyncResponse.describe(503, "<html>down</html>", "Couldn't save."));
		Assert.assertEquals("Key rejected. Reconnect this character.", SyncResponse.describe(401, "", "Couldn't save."));
		Assert.assertEquals("Couldn't save. The site didn't answer.", SyncResponse.describe(0, null, "Couldn't save."));
		Assert.assertEquals("Couldn't save. Unexpected answer from the site.",
			SyncResponse.describe(200, "<html></html>", "Couldn't save."));
	}

	@Test
	public void serverErrorsAreFiveHundreds()
	{
		Assert.assertTrue(SyncResponse.serverError(500));
		Assert.assertTrue(SyncResponse.serverError(503));
		Assert.assertFalse(SyncResponse.serverError(404));
		Assert.assertFalse(SyncResponse.serverError(0));
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
	public void clientErrorsAreReadForTheirCode()
	{
		Assert.assertTrue(SyncResponse.readsErrorBody(403));
		Assert.assertTrue(SyncResponse.readsErrorBody(409));
		Assert.assertFalse(SyncResponse.readsErrorBody(200));
		Assert.assertFalse(SyncResponse.readsErrorBody(500));
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
