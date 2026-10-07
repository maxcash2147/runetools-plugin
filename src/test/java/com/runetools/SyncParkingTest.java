package com.runetools;

import org.junit.Assert;
import org.junit.Test;

public class SyncParkingTest
{
	private static final String CA = "combat-achievements";
	private static final String CLOG = "collection-log";
	private static final String MAIN = "Zezima|111|key1";
	private static final String ALT = "Zezima Alt|222|key2";

	@Test
	public void nothingIsParkedByDefault()
	{
		SyncParking p = new SyncParking();
		Assert.assertFalse(p.isParked(CA, MAIN, 1L));
		Assert.assertFalse(p.isNameParked(CA, MAIN));
		Assert.assertFalse(p.anyNameParked(MAIN));
	}

	@Test
	public void nameParkHoldsEveryPayloadForThatCharacterOnly()
	{
		SyncParking p = new SyncParking();
		p.parkName(CA, MAIN);
		Assert.assertTrue(p.isParked(CA, MAIN, 1L));
		Assert.assertTrue(p.isParked(CA, MAIN, 2L));   // changing the payload doesn't help
		Assert.assertTrue(p.isNameParked(CA, MAIN));
		Assert.assertTrue(p.anyNameParked(MAIN));
		Assert.assertFalse(p.isParked(CLOG, MAIN, 1L));   // per endpoint
		Assert.assertFalse(p.anyNameParked(ALT));
	}

	@Test
	public void anotherCharacterOrKeyGetsAFreshTry()
	{
		SyncParking p = new SyncParking();
		p.parkName(CA, MAIN);
		Assert.assertFalse(p.isParked(CA, ALT, 1L));
		Assert.assertFalse(p.isParked(CA, "Zezima|111|key2", 1L));   // same character, newly-issued key
	}

	@Test
	public void payloadParkHoldsOnlyThatPayload()
	{
		SyncParking p = new SyncParking();
		p.parkPayload(CLOG, MAIN, 42L);
		Assert.assertTrue(p.isParked(CLOG, MAIN, 42L));
		Assert.assertFalse(p.isParked(CLOG, MAIN, 43L));   // a changed payload may be retried
		Assert.assertFalse(p.isNameParked(CLOG, MAIN));
		Assert.assertFalse(p.anyNameParked(MAIN));
	}

	@Test
	public void aLateBadRequestDoesNotNarrowANamePark()
	{
		SyncParking p = new SyncParking();
		p.parkName(CA, MAIN);
		p.parkPayload(CA, MAIN, 7L);
		Assert.assertTrue(p.isParked(CA, MAIN, 8L));
		Assert.assertTrue(p.isNameParked(CA, MAIN));
	}

	@Test
	public void successReleasesOnlyItsOwnCharactersPark()
	{
		SyncParking p = new SyncParking();
		p.parkName(CA, MAIN);
		p.release(CA, ALT);   // a late success for someone else changes nothing
		Assert.assertTrue(p.isNameParked(CA, MAIN));
		p.release(CA, MAIN);
		Assert.assertFalse(p.isParked(CA, MAIN, 1L));
		Assert.assertFalse(p.anyNameParked(MAIN));
	}
}
