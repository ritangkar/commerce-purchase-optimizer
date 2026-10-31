package com.birlaopus.optimizer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.hybris.bootstrap.annotations.UnitTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;


@UnitTest
public class BasketOptimizerTest
{
	@Test
	public void neverExceedsBudgetAndIsDeterministic()
	{
		final Random rnd = new Random(42);
		for (int run = 0; run < 200; run++)
		{
			final List<BasketOptimizer.Item> items = new ArrayList<>();
			for (int i = 0; i < 12; i++)
			{
				final long min = 1 + rnd.nextInt(2);
				items.add(new BasketOptimizer.Item("cand_" + i, 150 + rnd.nextInt(2000), min, min + rnd.nextInt(5),
						rnd.nextDouble(), 0.8));
			}
			final double budget = 1000 + rnd.nextInt(30000);
			final BasketOptimizer.Result a = BasketOptimizer.optimize(items, budget, 100);
			final BasketOptimizer.Result b = BasketOptimizer.optimize(items, budget, 100);
			assertTrue(a.getTotalCost() <= budget);
			assertEquals(a.getPacksById(), b.getPacksById());
		}
	}

	@Test
	public void respectsMinimumPacks()
	{
		final List<BasketOptimizer.Item> items = new ArrayList<>();
		items.add(new BasketOptimizer.Item("a", 500, 3, 5, 1d, 0.8)); // needs >= 1500
		final BasketOptimizer.Result r = BasketOptimizer.optimize(items, 1000d, 100);
		assertTrue(r.getPacksById().isEmpty());
		assertEquals("BUDGET_TOO_LOW", r.getReasonCode());
	}

	@Test
	public void noBudgetTakesEverythingAtMax()
	{
		final List<BasketOptimizer.Item> items = new ArrayList<>();
		items.add(new BasketOptimizer.Item("a", 100, 1, 4, 1d, 0.8));
		final BasketOptimizer.Result r = BasketOptimizer.optimize(items, null, 100);
		assertEquals(Long.valueOf(4), r.getPacksById().get("a"));
		assertEquals("NO_BUDGET_LIMIT", r.getReasonCode());
	}

	@Test
	public void prefersHigherScoreWhenBudgetTight()
	{
		final List<BasketOptimizer.Item> items = new ArrayList<>();
		items.add(new BasketOptimizer.Item("a", 1000, 1, 1, 0.2, 0.8));
		items.add(new BasketOptimizer.Item("b", 1000, 1, 1, 0.9, 0.8));
		final BasketOptimizer.Result r = BasketOptimizer.optimize(items, 1000d, 100);
		assertEquals(1, r.getPacksById().size());
		assertTrue(r.getPacksById().containsKey("b"));
	}

	@Test
	public void emptyInput()
	{
		assertEquals("NO_ITEMS", BasketOptimizer.optimize(new ArrayList<>(), 100d, 100).getReasonCode());
	}
}
