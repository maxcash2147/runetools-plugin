package com.runetools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** Setup collections are optional: without them the list is exactly what it always was. */
public class SetupCollectionsTest
{
	private static JsonObject json(String s)
	{
		return new JsonParser().parse(s).getAsJsonObject();
	}

	private static GearSetup setup(long id, String name, SetupCollection... in)
	{
		GearSetup g = new GearSetup();
		g.setId(id);
		g.setName(name);
		g.setCollections(new ArrayList<>(Arrays.asList(in)));
		return g;
	}

	private static SetupCollection coll(long id, String name, String role, int order)
	{
		return new SetupCollection(id, name, role, order);
	}

	@Test
	public void aSetupWithoutTheFieldIsInNoCollection()
	{
		GearSetup g = GearSetup.fromJson(json("{\"id\": 5, \"name\": \"Vorkath\"}"));
		Assert.assertTrue(g.getCollections().isEmpty());
	}

	@Test
	public void oneCollectionOrSeveralAreRead()
	{
		GearSetup one = GearSetup.fromJson(json("{\"id\": 5, \"name\": \"Tank\", "
			+ "\"collection\": {\"id\": 9, \"name\": \"3 Man Bandos\", \"role\": \"Tank\", \"order\": 1}}"));
		Assert.assertEquals(Collections.singletonList(coll(9, "3 Man Bandos", "Tank", 1)), one.getCollections());

		GearSetup many = GearSetup.fromJson(json("{\"id\": 5, \"name\": \"Ranger\", \"collections\": ["
			+ "{\"id\": 9, \"name\": \"3 Man Bandos\", \"role\": \"Ranger\", \"order\": 2},"
			+ "{\"id\": 10, \"name\": \"Duo Bandos\", \"role\": \"Ranger\", \"order\": 0}]}"));
		Assert.assertEquals(2, many.getCollections().size());
		Assert.assertEquals(10L, many.getCollections().get(1).getId());
	}

	@Test
	public void aMissingRoleOrOrderStillGroupsTheSetup()
	{
		String[] ok = {
			"\"collection\": {\"id\": 9, \"name\": \"Bandos\", \"order\": 1}",
			"\"collection\": {\"id\": 9, \"name\": \"Bandos\", \"role\": null, \"order\": 1}",
			"\"collection\": {\"id\": 9, \"name\": \"Bandos\", \"role\": \"\"}",
			"\"collection\": {\"id\": 9, \"name\": \"Bandos\", \"order\": null}",
		};
		for (String b : ok)
		{
			GearSetup g = GearSetup.fromJson(json("{\"id\": 5, \"name\": \"Tank\", " + b + "}"));
			Assert.assertEquals(b, 1, g.getCollections().size());
			SetupCollection c = g.getCollections().get(0);
			Assert.assertEquals(b, 9L, c.getId());
			Assert.assertEquals(b, "", c.getRole());
			Assert.assertTrue(b, c.getOrder() == 0 || c.getOrder() == 1);
		}
	}

	@Test
	public void aMalformedCollectionIsNoCollection()
	{
		String[] bad = {
			"\"collection\": \"3 Man Bandos\"",
			"\"collection\": {\"name\": \"x\", \"role\": \"Tank\", \"order\": 1}",
			"\"collection\": {\"id\": \"9\", \"name\": \"x\", \"role\": \"Tank\", \"order\": 1}",
			"\"collection\": {\"id\": 9.5, \"name\": \"x\", \"role\": \"Tank\", \"order\": 1}",
			"\"collection\": {\"id\": -1, \"name\": \"x\", \"role\": \"Tank\", \"order\": 1}",
			"\"collection\": {\"id\": 9, \"name\": 4, \"role\": \"Tank\", \"order\": 1}",
			"\"collection\": {\"id\": 9, \"name\": \"  \", \"role\": \"Tank\", \"order\": 1}",
			"\"collection\": {\"id\": 9, \"name\": \"x\", \"role\": [], \"order\": 1}",
			"\"collection\": {\"id\": 9, \"name\": \"x\", \"role\": \"Tank\", \"order\": \"1\"}",
			"\"collection\": {\"id\": 9, \"name\": \"x\", \"role\": \"Tank\", \"order\": {}}",
			"\"collection\": null",
			"\"collections\": {\"id\": 9}",
			"\"collections\": [1, \"a\", null, []]",
		};
		for (String b : bad)
		{
			GearSetup g = GearSetup.fromJson(json("{\"id\": 5, \"name\": \"Tank\", " + b + "}"));
			Assert.assertTrue(b, g.getCollections().isEmpty());
			Assert.assertEquals(b, 5L, g.getId());
		}
	}

	@Test
	public void namedInBothFieldsCountsOnce()
	{
		GearSetup g = GearSetup.fromJson(json("{\"id\": 5, "
			+ "\"collection\": {\"id\": 9, \"name\": \"Bandos\", \"role\": \"Tank\", \"order\": 1},"
			+ "\"collections\": [{\"id\": 9, \"name\": \"Bandos\", \"role\": \"Tank\", \"order\": 1}]}"));
		Assert.assertEquals(1, g.getCollections().size());
	}

	@Test
	public void withoutCollectionsTheListIsUnchanged()
	{
		List<GearSetup> list = Arrays.asList(setup(1, "Vorkath"), setup(2, "Zulrah"));
		List<SetupGroups.Row> rows = SetupGroups.rows(list);
		Assert.assertEquals(2, rows.size());
		Assert.assertEquals("Vorkath", rows.get(0).label);
		Assert.assertSame(list.get(1), rows.get(1).setup);
		Assert.assertFalse(rows.get(0).indented);
		Assert.assertNull(rows.get(0).tooltip);
	}

	@Test
	public void aCollectionsSetupsSitUnderItsNameInOrderByRole()
	{
		SetupCollection tank = coll(9, "3 Man Bandos", "Tank", 1);
		SetupCollection mage = coll(9, "3 Man Bandos", "Mage", 3);
		SetupCollection ranger = coll(9, "3 Man Bandos", "Ranger", 2);
		List<GearSetup> list = Arrays.asList(
			setup(1, "Vorkath"),
			setup(2, "Bandos mage", mage),
			setup(3, "Bandos tank", tank),
			setup(4, "Zulrah"),
			setup(5, "Bandos ranger", ranger));
		List<SetupGroups.Row> rows = SetupGroups.rows(list);
		String[] labels = new String[rows.size()];
		for (int i = 0; i < rows.size(); i++)
		{
			labels[i] = rows.get(i).label;
		}
		Assert.assertArrayEquals(new String[]{"Vorkath", "3 Man Bandos", "Tank", "Ranger", "Mage", "Zulrah"}, labels);
		Assert.assertNull("a header opens nothing", rows.get(1).setup);
		Assert.assertEquals("3 Man Bandos", rows.get(1).header);
		Assert.assertTrue(rows.get(2).indented);
		Assert.assertEquals("Bandos tank", rows.get(2).tooltip);
		Assert.assertEquals(3L, rows.get(2).setup.getId());
	}

	@Test
	public void aSetupInSeveralCollectionsIsListedUnderEach()
	{
		GearSetup shared = setup(1, "Ranger kit", coll(9, "Trio", "Ranger", 2), coll(10, "Duo", "Ranger", 1));
		GearSetup tank = setup(2, "Tank kit", coll(9, "Trio", "Tank", 1));
		List<SetupGroups.Row> rows = SetupGroups.rows(Arrays.asList(shared, tank));
		Assert.assertEquals(5, rows.size());
		Assert.assertEquals("Trio", rows.get(0).header);
		Assert.assertEquals("Tank", rows.get(1).label);
		Assert.assertEquals("Ranger", rows.get(2).label);
		Assert.assertEquals("Duo", rows.get(3).header);
		Assert.assertSame(shared, rows.get(4).setup);
	}

	@Test
	public void aSetupWithNoRoleIsLabelledByItsName()
	{
		List<SetupGroups.Row> rows = SetupGroups.rows(Collections.singletonList(setup(1, "Kit", coll(9, "Trio", "", 0))));
		Assert.assertEquals("Kit", rows.get(1).label);
		Assert.assertNull(rows.get(1).tooltip);
	}
}
