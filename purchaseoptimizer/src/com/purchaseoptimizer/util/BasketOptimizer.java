package com.purchaseoptimizer.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * Multiple-choice bounded knapsack over currency buckets (default 100 per bucket).
 * Each item can be taken as 0 or k packs (minPacks..maxPacks). Costs are rounded UP so the
 * budget is never exceeded. Deterministic: items are processed in id order and ties keep the
 * earlier / cheaper choice.
 */
public final class BasketOptimizer
{
	private BasketOptimizer()
	{
		// utility
	}

	public static final class Item
	{
		private final String id;
		private final double packCost;
		private final long minPacks;
		private final long maxPacks;
		private final double score;
		private final double diminishingFactor;

		public Item(final String id, final double packCost, final long minPacks, final long maxPacks, final double score,
				final double diminishingFactor)
		{
			this.id = id;
			this.packCost = packCost;
			this.minPacks = Math.max(1L, minPacks);
			this.maxPacks = Math.max(this.minPacks, maxPacks);
			this.score = score;
			this.diminishingFactor = diminishingFactor;
		}

		public String getId()
		{
			return id;
		}
	}

	public static final class Result
	{
		private final Map<String, Long> packsById;
		private final double totalCost;
		private final String reasonCode;

		Result(final Map<String, Long> packsById, final double totalCost, final String reasonCode)
		{
			this.packsById = packsById;
			this.totalCost = totalCost;
			this.reasonCode = reasonCode;
		}

		/** Item id -> number of packs chosen (only items with &gt;= 1 pack). */
		public Map<String, Long> getPacksById()
		{
			return packsById;
		}

		public double getTotalCost()
		{
			return totalCost;
		}

		/** OK, NO_BUDGET_LIMIT, BUDGET_TOO_LOW or NO_ITEMS. */
		public String getReasonCode()
		{
			return reasonCode;
		}
	}

	/**
	 * @param budget budget in currency units; null or &lt;= 0 means "no limit" (every item at max packs)
	 * @param granularity bucket size in currency units
	 */
	public static Result optimize(final List<Item> input, final Double budget, final double granularity)
	{
		final Map<String, Long> out = new LinkedHashMap<>();
		if (input == null || input.isEmpty())
		{
			return new Result(out, 0d, "NO_ITEMS");
		}
		final List<Item> items = new ArrayList<>(input);
		items.sort(Comparator.comparing(Item::getId));

		if (budget == null || budget <= 0d)
		{
			double total = 0d;
			for (final Item it : items)
			{
				out.put(it.id, it.maxPacks);
				total += it.packCost * it.maxPacks;
			}
			return new Result(out, total, "NO_BUDGET_LIMIT");
		}

		final double g = granularity <= 0d ? 100d : granularity;
		final int cap = (int) Math.floor(budget / g);
		final int n = items.size();
		final long[][] dp = new long[n + 1][cap + 1]; // value scaled by 1e6
		final int[][] choice = new int[n + 1][cap + 1];

		for (int i = 1; i <= n; i++)
		{
			final Item it = items.get(i - 1);
			final int unitBuckets = (int) Math.max(1d, Math.ceil(it.packCost / g));
			for (int b = 0; b <= cap; b++)
			{
				long best = dp[i - 1][b];
				int bestPacks = 0;
				for (long k = it.minPacks; k <= it.maxPacks; k++)
				{
					final long cost = (long) unitBuckets * k;
					if (cost > b)
					{
						break;
					}
					final long v = dp[i - 1][(int) (b - cost)] + scaledValue(it, k);
					if (v > best)
					{
						best = v;
						bestPacks = (int) k;
					}
				}
				dp[i][b] = best;
				choice[i][b] = bestPacks;
			}
		}

		int b = cap;
		double total = 0d;
		for (int i = n; i >= 1; i--)
		{
			final int packs = choice[i][b];
			if (packs > 0)
			{
				final Item it = items.get(i - 1);
				out.put(it.id, (long) packs);
				total += it.packCost * packs;
				b -= (int) Math.max(1d, Math.ceil(it.packCost / g)) * packs;
			}
		}
		// restore ascending id order for stable output
		final Map<String, Long> ordered = new LinkedHashMap<>();
		out.keySet().stream().sorted().forEach(k -> ordered.put(k, out.get(k)));
		return new Result(ordered, total, ordered.isEmpty() ? "BUDGET_TOO_LOW" : "OK");
	}

	private static long scaledValue(final Item it, final long packs)
	{
		double v = 0d;
		double f = 1d;
		for (long i = 1; i <= packs; i++)
		{
			v += it.score * f;
			f *= it.diminishingFactor;
		}
		return Math.round(v * 1_000_000d);
	}
}
