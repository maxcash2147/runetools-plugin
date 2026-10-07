package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 * The /state body must carry exactly what the server stores and what the consent text and README
 * promise: nothing more. A field added here has to be added to both of those as well.
 */
public class StateBodyTest
{
	private static final Set<String> ALWAYS = new HashSet<>(Arrays.asList(
		"rsn", "account_hash", "members", "gp", "free_slots", "offers", "inventory"));

	private static AccountSnapshot full()
	{
		JsonObject attack = new JsonObject();
		attack.addProperty("level", 99);
		attack.addProperty("xp", 13034431);
		JsonObject skills = new JsonObject();
		skills.add("attack", attack);
		JsonArray bank = new JsonArray();
		JsonObject coins = new JsonObject();
		coins.addProperty("item_id", 995);
		coins.addProperty("qty", 1000L);
		bank.add(coins);
		return AccountSnapshot.empty().toBuilder()
			.rsn("Zezima")
			.accountHash("123456789")
			.members(true)
			.gp(1000L)
			.freeSlots(8)
			.bank(bank)
			.skills(skills)
			.build();
	}

	@Test
	public void sendsExactlyTheStoredFields()
	{
		JsonObject body = RuneToolsApiClient.stateBody(full());
		Set<String> expected = new HashSet<>(ALWAYS);
		expected.add("bank");
		expected.add("skills");
		Assert.assertEquals(expected, body.keySet());
	}

	@Test
	public void neverSendsFieldsTheServerDiscards()
	{
		JsonObject body = RuneToolsApiClient.stateBody(full());
		for (String dropped : new String[]{"world", "quests", "slayer", "bronzeman", "ts"})
		{
			Assert.assertFalse(dropped + " should not be sent", body.has(dropped));
		}
	}

	@Test
	public void uncapturedBankAndSkillsAreLeftOutSoTheServerKeepsItsCopy()
	{
		AccountSnapshot s = AccountSnapshot.empty().toBuilder().rsn("Zezima").accountHash("1").build();
		JsonObject body = RuneToolsApiClient.stateBody(s);
		Assert.assertEquals(ALWAYS, body.keySet());
	}

	@Test
	public void skillsAreSentAsCaptured()
	{
		JsonObject body = RuneToolsApiClient.stateBody(full());
		JsonObject attack = body.getAsJsonObject("skills").getAsJsonObject("attack");
		Assert.assertEquals(99, attack.get("level").getAsInt());
		Assert.assertEquals(13034431, attack.get("xp").getAsInt());
	}
}
