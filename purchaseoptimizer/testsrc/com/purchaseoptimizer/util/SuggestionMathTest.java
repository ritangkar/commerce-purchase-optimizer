package com.purchaseoptimizer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.hybris.bootstrap.annotations.UnitTest;

import org.junit.Test;

import com.purchaseoptimizer.enums.ConfidenceTier;


@UnitTest
public class SuggestionMathTest
{
	private static double due(final double ratio)
	{
		return SuggestionMath.dueScore(ratio, 0.85, 1.30, 5.0);
	}

	@Test
	public void dueRatioBoundaries()
	{
		assertEquals(0d, due(0.84), 0d);
		assertTrue(due(0.85) > 0d);
		assertEquals(1d, due(1.30), 1e-9);
		assertTrue(due(1.31) > 0d && due(1.31) < 1d);
		assertEquals(0d, due(5.01), 0d);
		assertEquals(0d, due(Double.NaN), 0d);
	}

	@Test
	public void quantityRoundsToPackMultipleAndMoq()
	{
		assertEquals(12L, SuggestionMath.roundQuantity(10.2, 6, 1, 0, 0));
		assertEquals(18L, SuggestionMath.roundQuantity(2, 6, 18, 0, 0));
		assertEquals(0L, SuggestionMath.roundQuantity(0, 6, 1, 0, 0));
	}

	@Test
	public void quantityRespectsHistoricalCapAndMax()
	{
		assertEquals(12L, SuggestionMath.roundQuantity(30, 6, 1, 13, 0));
		assertEquals(24L, SuggestionMath.roundQuantity(30, 6, 1, 0, 25));
		assertEquals(0L, SuggestionMath.roundQuantity(30, 6, 12, 0, 8));
	}

	@Test
	public void trendFactorIsClamped()
	{
		assertEquals(1.15, SuggestionMath.trendFactor(50, 5, 10, 0.85, 1.15), 1e-9);
		assertEquals(0.85, SuggestionMath.trendFactor(-50, 5, 10, 0.85, 1.15), 1e-9);
		assertEquals(1d, SuggestionMath.trendFactor(5, 1, 10, 0.85, 1.15), 1e-9);
	}

	@Test
	public void sparseHistoryIsNeverHigh()
	{
		assertEquals(ConfidenceTier.EXPLORATORY, SuggestionMath.confidenceTier(2, 0.0, 10, 30));
		assertEquals(ConfidenceTier.MEDIUM, SuggestionMath.confidenceTier(4, 0.0, 10, 30));
		assertEquals(ConfidenceTier.HIGH, SuggestionMath.confidenceTier(5, 0.35, 90, 30));
		assertEquals(ConfidenceTier.MEDIUM, SuggestionMath.confidenceTier(5, 0.35, 91, 30));
		assertEquals(ConfidenceTier.MEDIUM, SuggestionMath.confidenceTier(5, 0.60, 10, 30));
		assertEquals(ConfidenceTier.EXPLORATORY, SuggestionMath.confidenceTier(6, 0.61, 10, 30));
	}

	@Test
	public void evidenceScoreInRange()
	{
		final double s = SuggestionMath.evidenceScore(8, 1d, 0d, 1d);
		assertEquals(1d, s, 1e-9);
		assertTrue(SuggestionMath.evidenceScore(0, 0d, 1d, 0.3) < 0.05);
	}
}
