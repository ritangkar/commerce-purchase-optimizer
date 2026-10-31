package com.birlaopus.optimizer.util;

import com.birlaopus.optimizer.enums.ConfidenceTier;


/**
 * Pure deterministic rules for due-ness, quantity, confidence and relevance.
 * All outputs describe ORDERING PATTERNS only (primary-sales data).
 */
public final class SuggestionMath
{
	private SuggestionMath()
	{
		// utility
	}

	public static double dueRatio(final double daysSinceLastOrder, final double medianIntervalDays)
	{
		if (medianIntervalDays <= 0d)
		{
			return Double.NaN;
		}
		return daysSinceLastOrder / medianIntervalDays;
	}

	/** Due-score in [0,1]; 0 outside [dueMin, dropAbove]. */
	public static double dueScore(final double ratio, final double dueMin, final double dueMax, final double dropAbove)
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

	public static double baseQuantity(final double medianQty, final double ewmaQty, final double medianWeight,
			final double ewmaWeight)
	{
		return medianWeight * medianQty + ewmaWeight * ewmaQty;
	}

	/** Relative change of the Theil-Sen trend across the series, clamped. */
	public static double trendFactor(final double slopePerOrder, final int orders, final double base, final double min,
			final double max)
	{
		if (base <= 0d || orders < 2)
		{
			return 1d;
		}
		final double factor = 1d + slopePerOrder * (orders - 1) / base;
		return Math.max(min, Math.min(max, factor));
	}

	/**
	 * Rounds a raw quantity to a pack multiple, applies MOQ, the historical cap and the product maximum.
	 *
	 * @param step pack multiple (&lt;=0 treated as 1)
	 * @param minQty minimum order quantity (&lt;=0 treated as step)
	 * @param historicalCap cap derived from history, e.g. 1.25 * P90 (&lt;=0 disables)
	 * @param maxQty product maximum (&lt;=0 disables)
	 * @return quantity (multiple of step), or 0 if nothing sensible remains
	 */
	public static long roundQuantity(final double raw, final long step, final long minQty, final double historicalCap,
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

	public static double recencyScore(final double daysSinceLastOrder)
	{
		return Math.max(0d, 1d - Math.min(1d, daysSinceLastOrder / 365d));
	}

	public static double sampleSizeScore(final int orders)
	{
		return Math.min(1d, orders / 8d);
	}

	public static double relevance(final double due, final double mix, final double affinity, final double recency,
			final double wDue, final double wMix, final double wAffinity, final double wRecency)
	{
		return wDue * due + wMix * mix + wAffinity * affinity + wRecency * recency;
	}

	/** Evidence-only confidence tiering. Sparse history can never be HIGH. */
	public static ConfidenceTier confidenceTier(final int ordersInWindow, final double intervalCv,
			final double daysSinceLast, final double medianIntervalDays)
	{
		if (ordersInWindow < 3)
		{
			return ConfidenceTier.EXPLORATORY;
		}
		if (ordersInWindow >= 5 && intervalCv <= 0.35d && medianIntervalDays > 0d
				&& daysSinceLast <= 3d * medianIntervalDays)
		{
			return ConfidenceTier.HIGH;
		}
		if (intervalCv <= 0.60d)
		{
			return ConfidenceTier.MEDIUM;
		}
		return ConfidenceTier.EXPLORATORY;
	}

	/** sourceWeight: individual history 1.0, peer 0.6, strategic-only 0.3. */
	public static double evidenceScore(final int orders, final double recency, final double intervalCv,
			final double sourceWeight)
	{
		return 0.4d * sampleSizeScore(orders) + 0.25d * recency + 0.25d * (1d - Math.min(1d, intervalCv))
				+ 0.10d * sourceWeight;
	}
}
