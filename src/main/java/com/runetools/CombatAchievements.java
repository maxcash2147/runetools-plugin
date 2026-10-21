package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Reads the player's Combat Achievements - the full task catalogue and per-task completion - straight
 * from the game cache, so the site can render a proper planner. Varbits and varps come from RuneLite's
 * gameval constants; the tier enums and task struct params have no public constant, so they are stable
 * cache ids (verified against osrs-reldo/task-json-store and the combat-achievements-tracker plugin):
 *
 * <ul>
 *   <li>one int-enum of task struct ids per tier ({@link #TIER_ENUMS});</li>
 *   <li>struct params for id/name/desc/tier/type/monster;</li>
 *   <li>completion is a bitmap packed across 20 VarPlayers ({@link #COMPLETION_VARPS}), indexed by
 *       the task's global id (param 1306): {@code varp[id/32] bit (id%32)}.</li>
 * </ul>
 *
 * <p>{@link #read} must run on the client thread; the cache structs only resolve while logged in.
 * Task counts and tier point thresholds change with game updates, so everything is enumerated at
 * runtime - only the ids here are hard-coded.
 */
final class CombatAchievements
{
	// One enum of task struct ids per tier, in tier order (points = tier index + 1).
	private static final int[] TIER_ENUMS = {3981, 3982, 3983, 3984, 3985, 3986};
	static final String[] TIER_NAMES = {"easy", "medium", "hard", "elite", "master", "grandmaster"};
	static final String[] TIER_LABELS = {"Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster"};

	// Point thresholds to COMPLETE each tier - game varbits, read at runtime because Jagex bumps
	// them whenever tasks are added.
	private static final int[] TIER_THRESHOLD_VARBITS = {
		VarbitID.CA_THRESHOLD_EASY, VarbitID.CA_THRESHOLD_MEDIUM, VarbitID.CA_THRESHOLD_HARD,
		VarbitID.CA_THRESHOLD_ELITE, VarbitID.CA_THRESHOLD_MASTER, VarbitID.CA_THRESHOLD_GRANDMASTER};

	// Completion bitmap varps, in bit order - non-contiguous game varp ids, order is load-bearing.
	private static final int[] COMPLETION_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1, VarPlayerID.CA_TASK_COMPLETED_2,
		VarPlayerID.CA_TASK_COMPLETED_3, VarPlayerID.CA_TASK_COMPLETED_4, VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6, VarPlayerID.CA_TASK_COMPLETED_7, VarPlayerID.CA_TASK_COMPLETED_8,
		VarPlayerID.CA_TASK_COMPLETED_9, VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13, VarPlayerID.CA_TASK_COMPLETED_14,
		VarPlayerID.CA_TASK_COMPLETED_15, VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19};

	// CA task struct params.
	private static final int P_ID = 1306;       // global task id (also the completion bit index)
	private static final int P_NAME = 1308;
	private static final int P_DESC = 1309;
	private static final int P_TYPE = 1311;
	private static final int P_MONSTER = 1312;
	private static final int ENUM_MONSTER = 3971;   // bossId -> name

	private static final String[] TYPE_NAMES = {
		null, "stamina", "perfection", "kill_count", "mechanical", "restriction", "speed"};   // 1-indexed

	private CombatAchievements()
	{
	}

	/** The catalogue + completion read from the cache, ready to POST, plus a change hash. */
	static final class Snapshot
	{
		final JsonArray tasks;
		final JsonArray completed;
		final JsonObject tiers;
		final int completedHash;
		// Per-tier stats (index = tier order) for the in-plugin progress display.
		final int[] tierDone;
		final int[] tierTotal;
		final int[] tierEarnedPts;
		final int[] tierPossiblePts;

		Snapshot(JsonArray tasks, JsonArray completed, JsonObject tiers, int completedHash,
				 int[] tierDone, int[] tierTotal, int[] tierEarnedPts, int[] tierPossiblePts)
		{
			this.tasks = tasks;
			this.completed = completed;
			this.tiers = tiers;
			this.completedHash = completedHash;
			this.tierDone = tierDone;
			this.tierTotal = tierTotal;
			this.tierEarnedPts = tierEarnedPts;
			this.tierPossiblePts = tierPossiblePts;
		}

		int totalEarnedPts()
		{
			int s = 0;
			for (int p : tierEarnedPts)
			{
				s += p;
			}
			return s;
		}
	}

	/** The point threshold to complete each tier, in tier order. Client thread. */
	static int[] readTierThresholds(Client client)
	{
		int[] out = new int[TIER_THRESHOLD_VARBITS.length];
		for (int i = 0; i < out.length; i++)
		{
			try
			{
				out[i] = client.getVarbitValue(TIER_THRESHOLD_VARBITS[i]);
			}
			catch (RuntimeException ex)
			{
				out[i] = 0;
			}
		}
		return out;
	}

	/** Read all tasks + completion. Returns null if the CA cache data isn't available yet. */
	static Snapshot read(Client client)
	{
		JsonArray tasks = new JsonArray();
		JsonArray completed = new JsonArray();
		int[] tierDone = new int[TIER_ENUMS.length];
		int[] tierTotal = new int[TIER_ENUMS.length];
		int[] tierEarnedPts = new int[TIER_ENUMS.length];
		int[] tierPossiblePts = new int[TIER_ENUMS.length];
		int hash = 1;
		boolean any = false;

		EnumComposition monsters = safeEnum(client, ENUM_MONSTER);

		for (int ti = 0; ti < TIER_ENUMS.length; ti++)
		{
			EnumComposition tierEnum = safeEnum(client, TIER_ENUMS[ti]);
			if (tierEnum == null)
			{
				continue;
			}
			for (int structId : tierEnum.getIntVals())
			{
				StructComposition s = client.getStructComposition(structId);
				if (s == null)
				{
					continue;
				}
				String name = s.getStringValue(P_NAME);
				if (name == null || name.isEmpty())
				{
					continue;
				}
				any = true;
				int id = s.getIntValue(P_ID);
				int typeId = s.getIntValue(P_TYPE);
				int bossId = s.getIntValue(P_MONSTER);
				String desc = s.getStringValue(P_DESC);
				String monster = monsters != null ? monsters.getStringValue(bossId) : null;
				String type = (typeId >= 1 && typeId < TYPE_NAMES.length) ? TYPE_NAMES[typeId] : null;

				JsonObject t = new JsonObject();
				t.addProperty("task_id", id);
				t.addProperty("name", name);
				t.addProperty("tier", TIER_NAMES[ti]);
				t.addProperty("points", ti + 1);
				if (type != null)
				{
					t.addProperty("type", type);
				}
				if (monster != null && !monster.isEmpty())
				{
					t.addProperty("monster", monster);
				}
				if (desc != null && !desc.isEmpty())
				{
					t.addProperty("description", desc);
				}
				tasks.add(t);
				tierTotal[ti]++;
				tierPossiblePts[ti] += ti + 1;

				int vi = id / 32, bit = id % 32;
				if (vi >= 0 && vi < COMPLETION_VARPS.length
					&& (client.getVarpValue(COMPLETION_VARPS[vi]) & (1 << bit)) != 0)
				{
					completed.add(id);
					tierDone[ti]++;
					tierEarnedPts[ti] += ti + 1;
					hash = hash * 31 + id;
				}
			}
		}

		if (!any)
		{
			return null;
		}
		JsonObject tiers = new JsonObject();
		for (int i = 0; i < TIER_NAMES.length; i++)
		{
			tiers.addProperty(TIER_NAMES[i], tierDone[i]);
		}
		return new Snapshot(tasks, completed, tiers, hash, tierDone, tierTotal, tierEarnedPts, tierPossiblePts);
	}

	private static EnumComposition safeEnum(Client client, int id)
	{
		try
		{
			return client.getEnum(id);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}
}
