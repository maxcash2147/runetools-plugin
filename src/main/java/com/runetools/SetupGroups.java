package com.runetools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The setups list as the panel shows it. A setup in no collection is a row of its own, where it has
 * always been. The first setup of a collection brings the collection's header there, followed by all
 * its setups in their {@code order}, each labelled by its role. A setup in several collections is
 * listed under each. Plain logic, so it can be unit-tested.
 */
final class SetupGroups
{
	/** One line of the list: a collection header ({@code setup} null) or a setup. */
	static final class Row
	{
		final String header;      // the collection's name, for a header row
		final GearSetup setup;    // the setup, for a setup row
		final String label;       // what the row reads: the role inside a collection, else the setup's name
		final String tooltip;     // the setup's name when the label is a role, else null
		final boolean indented;   // a setup inside a collection

		private Row(String header, GearSetup setup, String label, String tooltip, boolean indented)
		{
			this.header = header;
			this.setup = setup;
			this.label = label;
			this.tooltip = tooltip;
			this.indented = indented;
		}
	}

	private SetupGroups()
	{
	}

	static List<Row> rows(List<GearSetup> setups)
	{
		// Each collection's members, with the collection's first name, in first-seen order.
		Map<Long, List<GearSetup>> members = new LinkedHashMap<>();
		Map<Long, String> names = new LinkedHashMap<>();
		for (GearSetup s : setups)
		{
			for (SetupCollection c : collectionsOf(s))
			{
				members.computeIfAbsent(c.getId(), k -> new ArrayList<>()).add(s);
				names.putIfAbsent(c.getId(), c.getName());
			}
		}
		List<Row> out = new ArrayList<>();
		Set<Long> shown = new HashSet<>();
		for (GearSetup s : setups)
		{
			List<SetupCollection> mine = collectionsOf(s);
			if (mine.isEmpty())
			{
				out.add(new Row(null, s, name(s), null, false));
				continue;
			}
			for (SetupCollection c : mine)
			{
				if (!shown.add(c.getId()))
				{
					continue;
				}
				out.add(new Row(names.get(c.getId()), null, names.get(c.getId()), null, false));
				List<GearSetup> list = new ArrayList<>(members.get(c.getId()));
				final long cid = c.getId();
				list.sort(Comparator.comparingInt(g -> orderIn(g, cid)));   // stable: ties keep list order
				for (GearSetup g : list)
				{
					String role = roleIn(g, cid);
					boolean hasRole = role != null && !role.isEmpty();
					out.add(new Row(null, g, hasRole ? role : name(g), hasRole ? name(g) : null, true));
				}
			}
		}
		return out;
	}

	private static List<SetupCollection> collectionsOf(GearSetup s)
	{
		List<SetupCollection> c = s.getCollections();
		return c != null ? c : new ArrayList<>();
	}

	private static int orderIn(GearSetup g, long collectionId)
	{
		for (SetupCollection c : collectionsOf(g))
		{
			if (c.getId() == collectionId)
			{
				return c.getOrder();
			}
		}
		return 0;
	}

	private static String roleIn(GearSetup g, long collectionId)
	{
		for (SetupCollection c : collectionsOf(g))
		{
			if (c.getId() == collectionId)
			{
				return c.getRole();
			}
		}
		return null;
	}

	private static String name(GearSetup s)
	{
		return s.getName() != null ? s.getName() : "Setup";
	}
}
