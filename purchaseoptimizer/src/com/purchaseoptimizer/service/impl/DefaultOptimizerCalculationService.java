package com.purchaseoptimizer.service.impl;

import com.purchaseoptimizer.data.SuggestionCandidateData;
import com.purchaseoptimizer.enums.ConfidenceTier;
import com.purchaseoptimizer.service.OptimizerCalculationService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class DefaultOptimizerCalculationService implements OptimizerCalculationService
{
	// ------------------------------------------------------------------------------------------------ statistics

	@Override
	public double mean(final double[] v)
	{
		if (v == null || v.length == 0)
		{
			return 0d;
		}
		double s = 0d;
		for (final double d : v)
		{
			s += d;
		}
		return s / v.length;
	}

	@Override
	public double median(final double[] v)
	{
		return percentile(v, 0.5d);
	}

	@Override
	public double percentile(final double[] v, final double p)
	{
		if (v == null || v.length == 0)
		{
			return 0d;
		}
		final double[] s = v.clone();
		Arrays.sort(s);
		if (s.length == 1)
		{
			return s[0];
		}
		final double pos = Math.max(0d, Math.min(1d, p)) * (s.length - 1);
		final int lo = (int) Math.floor(pos);
		final int hi = (int) Math.ceil(pos);
		return s[lo] + (pos - lo) * (s[hi] - s[lo]);
	}

	@Override
	public double medianAbsoluteDeviation(final double[] v)
	{
		if (v == null || v.length == 0)
		{
			return 0d;
		}
		final double m = median(v);
		final double[] dev = new double[v.length];
		for (int i = 0; i < v.length; i++)
		{
			dev[i] = Math.abs(v[i] - m);
		}
		return median(dev);
	}

	@Override
	public double coefficientOfVariation(final double[] v)
	{
		if (v == null || v.length < 2)
		{
			return 0d;
		}
		final double m = mean(v);
		if (m == 0d)
		{
			return 0d;
		}
		double ss = 0d;
		for (final double d : v)
		{
			ss += (d - m) * (d - m);
		}
		return Math.sqrt(ss / v.length) / Math.abs(m);
	}

	@Override
	public double ewma(final double[] v, final double halfLifeOrders)
	{
		if (v == null || v.length == 0)
		{
			return 0d;
		}
		final double alpha = 1d - Math.pow(0.5d, 1d / Math.max(halfLifeOrders, 0.0001d));
		double e = v[0];
		for (int i = 1; i < v.length; i++)
		{
			e = alpha * v[i] + (1d - alpha) * e;
		}
		return e;
	}

	@Override
	public double theilSenSlope(final double[] v)
	{
		if (v == null || v.length < 2)
		{
			return 0d;
		}
		final int n = v.length;
		final double[] slopes = new double[n * (n - 1) / 2];
		int k = 0;
		for (int i = 0; i < n - 1; i++)
		{
			for (int j = i + 1; j < n; j++)
			{
				slopes[k++] = (v[j] - v[i]) / (j - i);
			}
		}
		return median(slopes);
	}

	@Override
	public double[] winsoriseUpper(final double[] v)
	{
		if (v == null)
		{
			return new double[0];
		}
		double fence = Double.POSITIVE_INFINITY;
		if (v.length >= 4)
		{
			final double q1 = percentile(v, 0.25d);
			final double q3 = percentile(v, 0.75d);
			fence = q3 + 1.5d * (q3 - q1);
		}
		final double[] out = new double[v.length];
		for (int i = 0; i < v.length; i++)
		{
			out[i] = Math.min(v[i], fence);
		}
		return out;
	}

	@Override
	public Double kthHighest(final double[] v, final int k)
	{
		if (v == null || k < 1 || v.length < k)
		{
			return null;
		}
		final double[] s = v.clone();
		Arrays.sort(s);
		return s[s.length - k];
	}

	// ------------------------------------------------------------------------------------------------ rules

	@Override
	public double dueRatio(final double daysSinceLastOrder, final double medianIntervalDays)
	{
		return medianIntervalDays <= 0d ? Double.NaN : daysSinceLastOrder / medianIntervalDays;
	}

	@Override
	public double dueScore(final double ratio, final double dueMin, final double dueMax, final double dropAbove)
	{
		if (Double.isNaN(ratio) || ratio < dueMin || ratio > dropAbove)
		{
			return 0d;
		}
		if (ratio < 1d)
		{
			return 0.7d + 0.3d * (ratio - dueMin) / Math.max(1d - dueMin, 0.0001d);
		}
		if (ratio <= dueMax)
		{
			return 1d;
		}
		return Math.max(0.3d, 1d - 0.3d * (ratio - dueMax));
	}

	@Override
	public double baseQuantity(final double medianQty, final double ewmaQty, final double medianWeight, final double ewmaWeight)
	{
		return medianWeight * medianQty + ewmaWeight * ewmaQty;
	}

	@Override
	public double trendFactor(final double slopePerOrder, final int orders, final double base, final double min,
			final double max)
	{
		if (base <= 0d || orders < 2)
		{
			return 1d;
		}
		return Math.max(min, Math.min(max, 1d + slopePerOrder * (orders - 1) / base));
	}

	@Override
	public long roundQuantity(final double raw, final long step, final long minQty, final double historicalCap,
			final long maxQty)
	{
		final long s = step <= 0 ? 1 : step;
		if (raw <= 0d)
		{
			return 0L;
		}
		long qty = (long) Math.ceil(raw / s) * s;
		final long moq = minQty <= 0 ? s : (long) Math.ceil((double) minQty / s) * s;
		qty = Math.max(qty, moq);
		if (historicalCap > 0d)
		{
			final long capped = (long) Math.floor(historicalCap / s) * s;
			qty = Math.min(qty, Math.max(capped, moq));
		}
		if (maxQty > 0)
		{
			final long maxAligned = (maxQty / s) * s;
			if (maxAligned < moq)
			{
				return 0L;
			}
			qty = Math.min(qty, maxAligned);
		}
		return qty;
	}

	@Override
	public double recencyScore(final double daysSinceLastOrder)
	{
		return Math.max(0d, 1d - Math.min(1d, daysSinceLastOrder / 365d));
	}

	@Override
	public double relevance(final double due, final double mix, final double affinity, final double recency, final double wDue,
			final double wMix, final double wAffinity, final double wRecency)
	{
		return wDue * due + wMix * mix + wAffinity * affinity + wRecency * recency;
	}

	@Override
	public ConfidenceTier confidenceTier(final int orders, final double intervalCv, final double daysSinceLast,
			final double medianIntervalDays)
	{
		if (orders < 3)
		{
			return ConfidenceTier.EXPLORATORY;
		}
		if (orders >= 5 && intervalCv <= 0.35d && medianIntervalDays > 0d && daysSinceLast <= 3d * medianIntervalDays)
		{
			return ConfidenceTier.HIGH;
		}
		return intervalCv <= 0.60d ? ConfidenceTier.MEDIUM : ConfidenceTier.EXPLORATORY;
	}

	@Override
	public double evidenceScore(final int orders, final double recency, final double intervalCv, final double sourceWeight)
	{
		return 0.4d * Math.min(1d, orders / 8d) + 0.25d * recency + 0.25d * (1d - Math.min(1d, intervalCv))
				+ 0.10d * sourceWeight;
	}

	// ------------------------------------------------------------------------------------------------ basket

	@Override
	public Map<String, Long> selectWithinBudget(final List<SuggestionCandidateData> input, final Double budget,
			final double granularity, final double diminishingFactor)
	{
		final Map<String, Long> out = new LinkedHashMap<>();
		if (input == null || input.isEmpty())
		{
			return out;
		}
		final List<SuggestionCandidateData> items = new ArrayList<>(input);
		items.sort(Comparator.comparing(SuggestionCandidateData::getCandidateId)); // deterministic

		if (budget == null || budget <= 0d)
		{
			for (final SuggestionCandidateData c : items)
			{
				out.put(c.getCandidateId(), maxPacks(c));
			}
			return out;
		}

		final double g = granularity <= 0d ? 100d : granularity;
		final int cap = (int) Math.floor(budget / g);
		final int n = items.size();
		final long[][] dp = new long[n + 1][cap + 1]; // value scaled by 1e6
		final int[][] choice = new int[n + 1][cap + 1];

		for (int i = 1; i <= n; i++)
		{
			final SuggestionCandidateData c = items.get(i - 1);
			final int unitBuckets = unitBuckets(c, g);
			final long minPacks = minPacks(c);
			final long maxPacks = maxPacks(c);
			for (int b = 0; b <= cap; b++)
			{
				long best = dp[i - 1][b];
				int bestPacks = 0;
				for (long k = minPacks; k <= maxPacks; k++)
				{
					final long cost = (long) unitBuckets * k;
					if (cost > b)
					{
						break;
					}
					final long v = dp[i - 1][(int) (b - cost)] + scaledValue(c, k, diminishingFactor);
					if (v > best) // strict: ties keep the cheaper / earlier choice
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
		final Map<String, Long> reversed = new LinkedHashMap<>();
		for (int i = n; i >= 1; i--)
		{
			final int packs = choice[i][b];
			if (packs > 0)
			{
				final SuggestionCandidateData c = items.get(i - 1);
				reversed.put(c.getCandidateId(), (long) packs);
				b -= unitBuckets(c, g) * packs;
			}
		}
		reversed.keySet().stream().sorted().forEach(k -> out.put(k, reversed.get(k)));
		return out;
	}

	private long pack(final SuggestionCandidateData c)
	{
		return Math.max(1L, c.getPackSize());
	}

	private long maxPacks(final SuggestionCandidateData c)
	{
		return Math.max(minPacks(c), c.getSuggestedQuantity() / pack(c));
	}

	private long minPacks(final SuggestionCandidateData c)
	{
		return Math.max(1L, (long) Math.ceil((double) c.getMinQuantity() / pack(c)));
	}

	private int unitBuckets(final SuggestionCandidateData c, final double g)
	{
		return (int) Math.max(1d, Math.ceil(c.getUnitPrice() * pack(c) / g));
	}

	private long scaledValue(final SuggestionCandidateData c, final long packs, final double diminishing)
	{
		final double score = Math.max(c.getRelevance(), 0.0001d);
		double v = 0d;
		double f = 1d;
		for (long i = 1; i <= packs; i++)
		{
			v += score * f;
			f *= diminishing;
		}
		return Math.round(v * 1_000_000d);
	}
}
