package com.runetools;

import com.google.gson.JsonArray;
import org.junit.Assert;
import org.junit.Test;

/** What one character left behind is never uploaded or counted as the next one's. */
public class CharacterStateTest
{
	private static void capture(CharacterState c, String rsn, String hash)
	{
		JsonArray bank = new JsonArray();
		bank.add(995);
		c.snapshot.updateAndGet(s -> s.toBuilder().rsn(rsn).accountHash(hash).gp(1_000L).bank(bank).build());
		c.inventoryCoins = 400;
		c.bankCoins = 600;
	}

	@Test
	public void theSameCharacterKeepsItsState()
	{
		CharacterState c = new CharacterState();
		Assert.assertFalse(c.adopt("111"));
		capture(c, "Main", "111");
		OfferTracker offers = c.offers();
		Assert.assertFalse(c.adopt("111"));
		Assert.assertEquals("Main", c.snapshot.get().rsn);
		Assert.assertEquals(600, c.bankCoins);
		Assert.assertSame(offers, c.offers());
	}

	@Test
	public void anotherAccountHashDropsEverythingCaptured()
	{
		CharacterState c = new CharacterState();
		c.adopt("111");
		capture(c, "Main", "111");
		OfferTracker offers = c.offers();
		SessionTracker session = c.session();
		Assert.assertTrue(c.adopt("222"));
		AccountSnapshot s = c.snapshot.get();
		Assert.assertNull(s.rsn);
		Assert.assertNull(s.accountHash);
		Assert.assertNull(s.bank);
		Assert.assertEquals(0L, s.gp);
		Assert.assertEquals(0, c.inventoryCoins);
		Assert.assertEquals(0, c.bankCoins);
		Assert.assertNotSame("the next character's offers start from fresh baselines", offers, c.offers());
		Assert.assertNotSame(session, c.session());
		Assert.assertEquals("222", c.owner());
	}

	@Test
	public void aLogoutDropsTheSessionButKeepsTheGeBaselines()
	{
		CharacterState c = new CharacterState();
		c.adopt("111");
		capture(c, "Main", "111");
		OfferTracker offers = c.offers();
		c.logout();
		Assert.assertNull(c.snapshot.get().accountHash);
		Assert.assertNull(c.snapshot.get().bank);
		Assert.assertEquals(0, c.bankCoins);
		Assert.assertNull(c.owner());
		// The same character logging back in counts the fills made while it was away.
		Assert.assertFalse(c.adopt("111"));
		Assert.assertSame(offers, c.offers());
		// Another character after the logout does not.
		Assert.assertTrue(c.adopt("222"));
		Assert.assertNotSame(offers, c.offers());
	}

	@Test
	public void anUnknownHashChangesNothing()
	{
		CharacterState c = new CharacterState();
		c.adopt("111");
		capture(c, "Main", "111");
		Assert.assertFalse(c.adopt(null));
		Assert.assertFalse(c.adopt(""));
		Assert.assertEquals("Main", c.snapshot.get().rsn);
	}

	@Test
	public void resetForgetsEverything()
	{
		CharacterState c = new CharacterState();
		c.adopt("111");
		capture(c, "Main", "111");
		OfferTracker offers = c.offers();
		c.reset();
		Assert.assertNull(c.owner());
		Assert.assertNull(c.snapshot.get().rsn);
		Assert.assertNotSame(offers, c.offers());
		Assert.assertFalse("nothing left to compare against", c.adopt("222"));
	}

	@Test
	public void anAcceptedBankIsRememberedUntilTheCharacterChanges()
	{
		CharacterState c = new CharacterState();
		c.adopt("111");
		Assert.assertFalse(c.bankAlreadySent(5));
		c.bankSent(5);
		Assert.assertTrue(c.bankAlreadySent(5));
		Assert.assertFalse(c.bankAlreadySent(6));
		c.adopt("222");
		Assert.assertFalse(c.bankAlreadySent(5));
		c.bankSent(7);
		c.logout();
		Assert.assertFalse(c.bankAlreadySent(7));
	}
}
