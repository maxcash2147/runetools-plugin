package com.runetools;

import com.google.gson.JsonObject;
import java.util.List;
import net.runelite.api.Client;

/**
 * Reads the player's current Slayer task from the game so the site's Slayer optimizer can use it.
 * Task id and counts come straight from varps/varbits; the creature and area names resolve through
 * the client-side DB tables the same way core RuneLite's own Slayer plugin does. Every read here
 * touches varps/varbits or the DB-table cache and MUST run on the client thread.
 *
 * <p>Verified ids (RuneLite master, {@code gameval} constants):
 * <ul>
 *   <li>VarPlayers: {@code SLAYER_TARGET=395} (creature), {@code SLAYER_COUNT=394} (amount left),
 *       {@code SLAYER_COUNT_ORIGINAL=4258} (assigned amount), {@code SLAYER_AREA=2096};</li>
 *   <li>Varbits: {@code SLAYER_MASTER=4067}, {@code SLAYER_POINTS=4068},
 *       {@code SLAYER_TASKS_COMPLETED=4069}, {@code SLAYER_WILDERNESS_TASKS_COMPLETED=5617},
 *       {@code SLAYER_TARGET_BOSSID=4723};</li>
 *   <li>DB tables: {@code SlayerTask=113} (col id 0, uppercase-name col 10),
 *       {@code SlayerArea=115} (col id 0, name col 3), {@code SlayerTaskSublist=116}
 *       (boss-id col 1, task-ref col 4).</li>
 * </ul>
 * The numeric varp/varbit ids are stable OSRS cache ids; the DB-table numbers are current but
 * cache-structure-dependent, so name resolution is best-effort and degrades to ids on any change.
 */
final class Slayer
{
	// VarPlayers -> client.getVarpValue
	private static final int VP_TARGET = 395;           // VarPlayerID.SLAYER_TARGET (task creature id)
	private static final int VP_COUNT = 394;            // VarPlayerID.SLAYER_COUNT (amount remaining)
	private static final int VP_COUNT_ORIGINAL = 4258;  // VarPlayerID.SLAYER_COUNT_ORIGINAL (assigned amount)
	private static final int VP_AREA = 2096;            // VarPlayerID.SLAYER_AREA (task location id)

	// Varbits -> client.getVarbitValue
	private static final int VB_MASTER = 4067;          // VarbitID.SLAYER_MASTER
	private static final int VB_POINTS = 4068;          // VarbitID.SLAYER_POINTS
	private static final int VB_STREAK = 4069;          // VarbitID.SLAYER_TASKS_COMPLETED
	private static final int VB_STREAK_WILDY = 5617;    // VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED
	private static final int VB_TARGET_BOSSID = 4723;   // VarbitID.SLAYER_TARGET_BOSSID

	private static final int KRYSTILIA_MASTER = 7;      // only master id confirmed in core; selects the wildy streak
	private static final int TASK_ID_BOSSES = 98;       // sentinel task id whose real creature is the boss id

	// Client-side DB tables (DBTableID.*) for name resolution.
	private static final int DBT_SLAYER_TASK = 113;     // SlayerTask.ID
	private static final int COL_TASK_ID = 0;           // SlayerTask.COL_ID
	private static final int COL_TASK_NAME = 10;        // SlayerTask.COL_NAME_UPPERCASE
	private static final int DBT_SLAYER_AREA = 115;     // SlayerArea.ID
	private static final int COL_AREA_ID = 0;           // SlayerArea.COL_AREA_ID
	private static final int COL_AREA_NAME = 3;         // SlayerArea.COL_AREA_NAME_IN_HELPER
	private static final int DBT_SLAYER_SUBLIST = 116;  // SlayerTaskSublist.ID
	private static final int COL_SUBLIST_BOSSID = 1;    // SlayerTaskSublist.COL_TASK_SUBTABLE_ID
	private static final int COL_SUBLIST_TASK = 4;      // SlayerTaskSublist.COL_TASK

	// Changes to any of these mean the task state may have changed - the plugin watches them.
	static final int[] WATCH_VARPS = {VP_TARGET, VP_COUNT, VP_COUNT_ORIGINAL, VP_AREA};
	static final int[] WATCH_VARBITS = {VB_MASTER, VB_POINTS, VB_STREAK, VB_STREAK_WILDY, VB_TARGET_BOSSID};

	private Slayer()
	{
	}

	/**
	 * The current task as JSON, or null when the player has no active task. Client thread only.
	 * Shape: {@code {task?, task_id, amount, amount_assigned?, master_id, area?, area_id?, points, streak}}.
	 */
	static JsonObject read(Client client)
	{
		int taskId = client.getVarpValue(VP_TARGET);
		int amount = client.getVarpValue(VP_COUNT);
		if (taskId <= 0 || amount <= 0)
		{
			return null;   // no creature assigned, or the task is already finished
		}

		int master = client.getVarbitValue(VB_MASTER);
		int points = client.getVarbitValue(VB_POINTS);
		int streak = master == KRYSTILIA_MASTER
			? client.getVarbitValue(VB_STREAK_WILDY)
			: client.getVarbitValue(VB_STREAK);

		JsonObject o = new JsonObject();
		o.addProperty("task_id", taskId);
		o.addProperty("amount", amount);
		int assigned = client.getVarpValue(VP_COUNT_ORIGINAL);
		if (assigned > 0)
		{
			o.addProperty("amount_assigned", assigned);
		}
		o.addProperty("master_id", master);
		o.addProperty("points", points);
		o.addProperty("streak", streak);

		String task = taskName(client, taskId);
		if (task != null)
		{
			o.addProperty("task", task);
		}
		int area = client.getVarpValue(VP_AREA);
		if (area > 0)
		{
			o.addProperty("area_id", area);
			String areaName = areaName(client, area);
			if (areaName != null)
			{
				o.addProperty("area", areaName);
			}
		}
		return o;
	}

	/** A stable change hash so an identical re-read is skipped. */
	static int hash(JsonObject o)
	{
		return o == null ? 0 : o.toString().hashCode();
	}

	/** Resolve the creature name from the DB tables (title-cased). Best-effort - may return null. */
	private static String taskName(Client client, int taskId)
	{
		try
		{
			int row;
			if (taskId == TASK_ID_BOSSES)
			{
				int bossId = client.getVarbitValue(VB_TARGET_BOSSID);
				List<Integer> rows = client.getDBRowsByValue(DBT_SLAYER_SUBLIST, COL_SUBLIST_BOSSID, 0, bossId);
				if (rows == null || rows.isEmpty())
				{
					return null;
				}
				Object taskRef = client.getDBTableField(rows.get(0), COL_SUBLIST_TASK, 0)[0];
				row = ((Number) taskRef).intValue();
			}
			else
			{
				List<Integer> rows = client.getDBRowsByValue(DBT_SLAYER_TASK, COL_TASK_ID, 0, taskId);
				if (rows == null || rows.isEmpty())
				{
					return null;
				}
				row = rows.get(0);
			}
			Object name = client.getDBTableField(row, COL_TASK_NAME, 0)[0];
			return titleCase((String) name);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static String areaName(Client client, int areaId)
	{
		try
		{
			List<Integer> rows = client.getDBRowsByValue(DBT_SLAYER_AREA, COL_AREA_ID, 0, areaId);
			if (rows == null || rows.isEmpty())
			{
				return null;
			}
			Object name = client.getDBTableField(rows.get(0), COL_AREA_NAME, 0)[0];
			return titleCase((String) name);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	/** DB names come back UPPERCASE (e.g. "ABYSSAL DEMONS"); present them title-cased. */
	private static String titleCase(String s)
	{
		if (s == null || s.isEmpty())
		{
			return null;
		}
		StringBuilder sb = new StringBuilder(s.length());
		boolean start = true;
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			sb.append(start && Character.isLetter(c) ? Character.toUpperCase(c) : Character.toLowerCase(c));
			// A space or hyphen starts a new word; an apostrophe does NOT (keep "Vet'ion", "K'ril").
			start = c == ' ' || c == '-';
		}
		return sb.toString();
	}
}
