package com.purchaseoptimizer.service.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.hybris.bootstrap.annotations.UnitTest;

import com.purchaseoptimizer.data.SuggestionCandidateData;
import com.purchaseoptimizer.enums.ConfidenceTier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Test;


@UnitTest
public class DefaultOptimizerCalculationServiceTest
{
	private static final double D = 1e-9;
	private final DefaultOptimizerCalculationService calc = new DefaultOptimizerCalculationService();

	// ---- statistics
	@Test
	public void emptyAndSingleInputsAreSafe()
	{
		assertEquals(0d, calc.median(new double[0]), D);
		assertEquals(0d, calc.median(null), D);
		assertEquals(7d, calc.median(new double[] { 7 }), D);
		assertEquals(0d, calc.medianAbsoluteDeviation(new double[0]), D);
		assertEquals(0d, calc.theilSenSlope(new double[] { 5 }), D);
		assertEquals(0d, calc.ewma(new double[0], 3), D);
		assertEquals(4d, calc.ewma(new double[] { 4 }, 3), D);
	}

	@Test
	public void medianMadTiesAndTrend()
	{
		assertEquals(5d, calc.median(new double[] { 9, 1, 5 }), D);
		assertEquals(3d, calc.median(new double[] { 1, 5 }), D);
		assertEquals(1d, calc.medianAbsoluteDeviation(new double[] { 1, 2, 3, 4, 100 }), D);
		assertEquals(0d, calc.coefficientOfVariation(new double[] { 30, 30, 30 }), D);
		assertEquals(0d, calc.theilSenSlope(new double[] { 4, 4, 4, 4 }), D);
		assertEquals(2d, calc.theilSenSlope(new double[] { 2, 4, 6, 8, 10 }), D);
		final double e = calc.ewma(new double[] { 10, 10, 10, 20 }, 3);
		assertTrue(e > 10d && e < 20d);
	}

	@Test
	public void winsorisingAndKthHighest()
	{
		final double[] w = calc.winsoriseUpper(new double[] { 10, 11, 10, 12, 11, 500 });
		assertTrue(w[5] < 500d);
		assertEquals(500d, calc.winsoriseUpper(new double[] { 10, 500, 11 })[1], D);
		assertNull(calc.kthHighest(new double[] { 1, 2, 3 }, 4));
		assertEquals(5d, calc.kthHighest(new double[] { 9, 8, 7, 5, 4, 3, 2, 1 }, 4), D);
		assertEquals(4d, calc.kthHighest(new double[] { 9, 8, 7, 5, 4, 3, 2, 1 }, 5), D);
		assertEquals(10d, calc.kthHighest(new double[] { 10, 10, 10, 5 }, 3), D);
	}

	// ---- rules
	private double due(final double ratio)
	{
		return calc.dueScore(ratio, 0.85, 1.30, 5.0);
	}

	@Test
	public void dueRatioBoundaries()
	{
		assertEquals(0d, due(0.84), 0d);
		assertTrue(due(0.85) > 0d);
		assertEquals(1d, due(1.30), D);
		assertTrue(due(1.31) > 0d && due(1.31) < 1d);
		assertEquals(0d, due(5.01), 0d);
		assertEquals(0d, due(Double.NaN), 0d);
	}

	@Test
	public void quantityRules()
	{
		assertEquals(12L, calc.roundQuantity(10.2, 6, 1, 0, 0));
		assertEquals(18L, calc.roundQuantity(2, 6, 18, 0, 0));
		assertEquals(0L, calc.roundQuantity(0, 6, 1, 0, 0));
		assertEquals(12L, calc.roundQuantity(30, 6, 1, 13, 0));
		assertEquals(24L, calc.roundQuantity(30, 6, 1, 0, 25));
		assertEquals(0L, calc.roundQuantity(30, 6, 12, 0, 8));
		assertEquals(1.15, calc.trendFactor(50, 5, 10, 0.85, 1.15), D);
		assertEquals(0.85, calc.trendFactor(-50, 5, 10, 0.85, 1.15), D);
		assertEquals(1d, calc.trendFactor(5, 1, 10, 0.85, 1.15), D);
	}

	@Test
	public void sparseHistoryIsNeverHigh()
	{
		assertEquals(ConfidenceTier.EXPLORATORY, calc.confidenceTier(2, 0.0, 10, 30));
		assertEquals(ConfidenceTier.MEDIUM, calc.confidenceTier(4, 0.0, 10, 30));
		assertEquals(ConfidenceTier.HIGH, calc.confidenceTier(5, 0.35, 90, 30));
		assertEquals(ConfidenceTier.MEDIUM, calc.confidenceTier(5, 0.35, 91, 30));
		assertEquals(ConfidenceTier.EXPLORATORY, calc.confidenceTier(6, 0.61, 10, 30));
		assertEquals(1d, calc.evidenceScore(8, 1d, 0d, 1d), D);
	}

	// ---- basket
	private SuggestionCandidateData cand(final String id, final double price, final long min, final long max,
			final double relevance)
	{
		final SuggestionCandidateData c = new SuggestionCandidateData();
		c.setCandidateId(id);
		c.setUnitPrice(price);
		c.setPackSize(1L);
		c.setMinQuantity(min);
		c.setSuggestedQuantity(max);
		c.setRelevance(relevance);
		return c;
	}

	@Test
	public void basketNeverExceedsBudgetAndIsDeterministic()
	{
		final Random rnd = new Random(42);
		for (int run = 0; run < 200; run++)
		{
			final List<SuggestionCandidateData> items = new ArrayList<>();
			for (int i = 0; i < 12; i++)
			{
				final long min = 1 + rnd.nextInt(2);
				items.add(cand("cand_" + i, 150 + rnd.nextInt(2000), min, min + rnd.nextInt(5), rnd.nextDouble()));
			}
			final double budget = 1000 + rnd.nextInt(30000);
			final Map<String, Long> a = calc.selectWithinBudget(items, budget, 100, 0.8);
			final Map<String, Long> b = calc.selectWithinBudget(items, budget, 100, 0.8);
			double cost = 0;
			for (final SuggestionCandidateData c : items)
			{
				cost += c.getUnitPrice() * a.getOrDefault(c.getCandidateId(), 0L);
			}
			assertTrue(cost <= budget);
			assertEquals(a, b);
		}
	}

	@Test
	public void basketRespectsMoqNoBudgetAndScore()
	{
		final List<SuggestionCandidateData> one = new ArrayList<>();
		one.add(cand("a", 500, 3, 5, 1d));
		assertTrue(calc.selectWithinBudget(one, 1000d, 100, 0.8).isEmpty());
		assertEquals(Long.valueOf(5), calc.selectWithinBudget(one, null, 100, 0.8).get("a"));

		final List<SuggestionCandidateData> two = new ArrayList<>();
		two.add(cand("a", 1000, 1, 1, 0.2));
		two.add(cand("b", 1000, 1, 1, 0.9));
		final Map<String, Long> r = calc.selectWithinBudget(two, 1000d, 100, 0.8);
		assertEquals(1, r.size());
		assertTrue(r.containsKey("b"));
	}
}
