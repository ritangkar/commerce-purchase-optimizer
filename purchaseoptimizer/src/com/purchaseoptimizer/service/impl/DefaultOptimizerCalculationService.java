package com.purchaseoptimizer.service.impl;

import com.purchaseoptimizer.data.SuggestionCandidateData;
import com.purchaseoptimizer.enums.CandidateGroup;
import com.purchaseoptimizer.enums.ConfidenceTier;
import com.purchaseoptimizer.enums.RecoStatus;
import com.purchaseoptimizer.service.OptimizerCalculationService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;


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

	// ------------------------------------------------------------------------------------------------ strategic blend

	@Override
	public boolean isTransitionAllowed(final RecoStatus from, final RecoStatus to)
	{
		if (from == null || to == null || from == to)
		{
			return true; // no change
		}
		switch (from)
		{
			case DRAFT:
				return to == RecoStatus.IN_REVIEW;
			case IN_REVIEW:
				return to == RecoStatus.DRAFT || to == RecoStatus.APPROVED;
			case APPROVED:
				return to == RecoStatus.ACTIVE || to == RecoStatus.EXPIRED || to == RecoStatus.DRAFT;
			case ACTIVE:
				return to == RecoStatus.EXPIRED;
			default:
				return false; // EXPIRED is final
		}
	}

	@Override
	public boolean isHoldout(final String secret, final String campaignCode, final String dealerId, final int holdoutPercent)
	{
		if (holdoutPercent <= 0)
		{
			return false;
		}
		if (holdoutPercent >= 100)
		{
			return true;
		}
		try
		{
			final Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			final byte[] h = mac.doFinal((campaignCode + "|" + dealerId).getBytes(StandardCharsets.UTF_8));
			final long v = ((h[0] & 0xffL) << 24) | ((h[1] & 0xffL) << 16) | ((h[2] & 0xffL) << 8) | (h[3] & 0xffL);
			return (v % 100L) < holdoutPercent;
		}
		catch (final Exception e)
		{
			throw new IllegalStateException("HMAC unavailable", e);
		}
	}

	@Override
	public boolean gateOpen(final int ordersInRelatedCategory, final int minOrders)
	{
		return ordersInRelatedCategory >= Math.max(1, minOrders);
	}

	@Override
	public double finalScore(final double relevance, final double boost, final double boostCap, final boolean gateOpen)
	{
		final double b = gateOpen ? Math.max(0d, Math.min(boost, boostCap)) : 0d;
		return Math.max(0d, relevance + b);
	}

	@Override
	public long scaleBundleQuantity(final double typicalQty, final double qtyRatio, final long step, final long minQty,
			final long maxQty)
	{
		if (typicalQty <= 0d || qtyRatio <= 0d)
		{
			return 0L;
		}
		return roundQuantity(typicalQty * qtyRatio, step, minQty, 0d, maxQty);
	}

	private boolean isStrategic(final SuggestionCandidateData c)
	{
		return CandidateGroup.STRATEGIC.equals(c.getGroup()) || CandidateGroup.BUNDLE_CAMPAIGN.equals(c.getGroup());
	}

	private double value(final SuggestionCandidateData c)
	{
		return c.getUnitPrice() * c.getSuggestedQuantity();
	}

	/** Removal unit key: bundle code for bundle lines, candidate id otherwise. */
	private String unitKey(final SuggestionCandidateData c)
	{
		return c.getBundleCode() != null && !c.getBundleCode().isEmpty() ? "B:" + c.getBundleCode() : "L:" + c.getCandidateId();
	}

	@Override
	public List<SuggestionCandidateData> enforceStrategicCaps(final List<SuggestionCandidateData> selected,
			final double lineCap, final double valueCap)
	{
		final List<SuggestionCandidateData> out = new ArrayList<>(selected);
		while (true)
		{
			final int n = out.size();
			double total = 0d;
			double strategicValue = 0d;
			int strategicLines = 0;
			for (final SuggestionCandidateData c : out)
			{
				total += value(c);
				if (isStrategic(c))
				{
					strategicLines++;
					strategicValue += value(c);
				}
			}
			final boolean linesOk = strategicLines <= Math.floor(lineCap * n + 1e-9);
			final boolean valueOk = total <= 0d || strategicValue / total <= valueCap + 1e-9;
			if (strategicLines == 0 || (linesOk && valueOk))
			{
				return out;
			}
			// weakest strategic unit = lowest average relevance, ties by key (deterministic)
			final Map<String, double[]> units = new LinkedHashMap<>(); // key -> {sumRelevance, count}
			for (final SuggestionCandidateData c : out)
			{
				if (isStrategic(c))
				{
					final double[] u = units.computeIfAbsent(unitKey(c), k -> new double[2]);
					u[0] += c.getRelevance();
					u[1]++;
				}
			}
			String weakest = null;
			double weakestScore = Double.MAX_VALUE;
			for (final Map.Entry<String, double[]> e : units.entrySet())
			{
				final double avg = e.getValue()[0] / e.getValue()[1];
				if (avg < weakestScore || (avg == weakestScore && e.getKey().compareTo(weakest) < 0))
				{
					weakest = e.getKey();
					weakestScore = avg;
				}
			}
			final String drop = weakest;
			out.removeIf(c -> isStrategic(c) && unitKey(c).equals(drop));
		}
	}

	// ------------------------------------------------------------------------------------------------ basket

	/** A decision unit: one ordinary candidate (0..max packs) or a whole bundle (all lines or nothing). */
	private static final class Unit
	{
		final List<SuggestionCandidateData> lines = new ArrayList<>();
		boolean bundle;
	}

	@Override
	public Map<String, Long> selectWithinBudget(final List<SuggestionCandidateData> input, final Double budget,
			final double granularity, final double diminishingFactor)
	{
		final Map<String, Long> out = new LinkedHashMap<>();
		if (input == null || input.isEmpty())
		{
			return out;
		}
		final List<SuggestionCandidateData> sorted = new ArrayList<>(input);
		sorted.sort(Comparator.comparing(SuggestionCandidateData::getCandidateId)); // deterministic

		final Map<String, Unit> byBundle = new LinkedHashMap<>();
		final List<Unit> units = new ArrayList<>();
		for (final SuggestionCandidateData c : sorted)
		{
			final boolean inBundle = c.getBundleCode() != null && !c.getBundleCode().isEmpty();
			final Unit u = inBundle ? byBundle.computeIfAbsent(c.getBundleCode(), k -> {
				final Unit nu = new Unit();
				nu.bundle = true;
				units.add(nu);
				return nu;
			}) : new Unit();
			u.lines.add(c);
			if (!inBundle)
			{
				units.add(u);
			}
		}

		if (budget == null || budget <= 0d)
		{
			for (final Unit u : units)
			{
				for (final SuggestionCandidateData c : u.lines)
				{
					out.put(c.getCandidateId(), u.bundle ? Math.max(1L, c.getSuggestedQuantity() / pack(c)) : maxPacks(c));
				}
			}
			return sortedKeys(out);
		}

		final double g = granularity <= 0d ? 100d : granularity;
		final int cap = (int) Math.floor(budget / g);
		final int n = units.size();
		final long[][] dp = new long[n + 1][cap + 1]; // value scaled by 1e6
		final int[][] choice = new int[n + 1][cap + 1];

		for (int i = 1; i <= n; i++)
		{
			final Unit u = units.get(i - 1);
			for (int b = 0; b <= cap; b++)
			{
				long best = dp[i - 1][b];
				int bestPacks = 0;
				if (u.bundle)
				{
					final long cost = bundleBuckets(u, g);
					if (cost <= b)
					{
						final long v = dp[i - 1][(int) (b - cost)] + bundleValue(u);
						if (v > best)
						{
							best = v;
							bestPacks = 1;
						}
					}
				}
				else
				{
					final SuggestionCandidateData c = u.lines.get(0);
					final int unitBuckets = unitBuckets(c, g);
					for (long k = minPacks(c); k <= maxPacks(c); k++)
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
				}
				dp[i][b] = best;
				choice[i][b] = bestPacks;
			}
		}

		int b = cap;
		for (int i = n; i >= 1; i--)
		{
			final int packs = choice[i][b];
			if (packs > 0)
			{
				final Unit u = units.get(i - 1);
				if (u.bundle)
				{
					for (final SuggestionCandidateData c : u.lines)
					{
						out.put(c.getCandidateId(), Math.max(1L, c.getSuggestedQuantity() / pack(c)));
					}
					b -= (int) bundleBuckets(u, g);
				}
				else
				{
					out.put(u.lines.get(0).getCandidateId(), (long) packs);
					b -= unitBuckets(u.lines.get(0), g) * packs;
				}
			}
		}
		return sortedKeys(out);
	}

	private Map<String, Long> sortedKeys(final Map<String, Long> in)
	{
		final Map<String, Long> ordered = new LinkedHashMap<>();
		in.keySet().stream().sorted().forEach(k -> ordered.put(k, in.get(k)));
		return ordered;
	}

	private long bundleBuckets(final Unit u, final double g)
	{
		long sum = 0;
		for (final SuggestionCandidateData c : u.lines)
		{
			sum += (long) unitBuckets(c, g) * Math.max(1L, c.getSuggestedQuantity() / pack(c));
		}
		return sum;
	}

	private long bundleValue(final Unit u)
	{
		long sum = 0;
		for (final SuggestionCandidateData c : u.lines)
		{
			sum += scaledValue(c, Math.max(1L, c.getSuggestedQuantity() / pack(c)), 1d);
		}
		return sum;
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
