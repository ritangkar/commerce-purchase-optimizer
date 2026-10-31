package com.purchaseoptimizer.util;

import java.util.Arrays;


/**
 * Pure statistical helpers. No platform dependencies, null/empty safe.
 * Series are expected oldest -> newest where order matters (ewma, slope).
 */
public final class PurchaseStatistics
{
	private PurchaseStatistics()
	{
		// utility
	}

	public static double mean(final double[] v)
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

	public static double median(final double[] v)
	{
		return percentile(v, 0.5d);
	}

	/** Linear-interpolated percentile (p in [0,1]). Returns 0 for empty input. */
	public static double percentile(final double[] v, final double p)
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

	/** Median absolute deviation. */
	public static double mad(final double[] v)
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

	/** Population coefficient of variation; 0 when mean is 0 or n &lt; 2. */
	public static double coefficientOfVariation(final double[] v)
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

	/**
	 * Exponentially weighted moving average over an oldest->newest series.
	 * Weight of an observation halves every {@code halfLifeOrders} observations.
	 */
	public static double ewma(final double[] v, final double halfLifeOrders)
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

	/** Theil-Sen slope per observation index (median of pairwise slopes). 0 for n &lt; 2. */
	public static double theilSenSlope(final double[] v)
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

	/** Upper IQR fence Q3 + 1.5*IQR. Returns +infinity when n &lt; 4 (no winsorising). */
	public static double upperFence(final double[] v)
	{
		if (v == null || v.length < 4)
		{
			return Double.POSITIVE_INFINITY;
		}
		final double q1 = percentile(v, 0.25d);
		final double q3 = percentile(v, 0.75d);
		return q3 + 1.5d * (q3 - q1);
	}

	/** Caps values above the upper IQR fence; order preserved. */
	public static double[] winsoriseUpper(final double[] v)
	{
		if (v == null)
		{
			return new double[0];
		}
		final double fence = upperFence(v);
		final double[] out = new double[v.length];
		for (int i = 0; i < v.length; i++)
		{
			out[i] = Math.min(v[i], fence);
		}
		return out;
	}

	/**
	 * k-th highest value (1-based, ties counted as separate observations), i.e. a level reached at least k times.
	 * Returns null if fewer than k values.
	 */
	public static Double kthHighest(final double[] v, final int k)
	{
		if (v == null || k < 1 || v.length < k)
		{
			return null;
		}
		final double[] s = v.clone();
		Arrays.sort(s);
		return s[s.length - k];
	}
}
