package com.birlaopus.optimizer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import de.hybris.bootstrap.annotations.UnitTest;

import org.junit.Test;


@UnitTest
public class PurchaseStatisticsTest
{
	private static final double D = 1e-9;

	@Test
	public void emptyAndSingleInputsAreSafe()
	{
		assertEquals(0d, PurchaseStatistics.median(new double[0]), D);
		assertEquals(0d, PurchaseStatistics.median(null), D);
		assertEquals(7d, PurchaseStatistics.median(new double[] { 7 }), D);
		assertEquals(0d, PurchaseStatistics.mad(new double[0]), D);
		assertEquals(0d, PurchaseStatistics.theilSenSlope(new double[] { 5 }), D);
		assertEquals(0d, PurchaseStatistics.ewma(new double[0], 3), D);
		assertEquals(4d, PurchaseStatistics.ewma(new double[] { 4 }, 3), D);
	}

	@Test
	public void medianAndMad()
	{
		assertEquals(5d, PurchaseStatistics.median(new double[] { 9, 1, 5 }), D);
		assertEquals(3d, PurchaseStatistics.median(new double[] { 1, 5 }), D);
		assertEquals(1d, PurchaseStatistics.mad(new double[] { 1, 2, 3, 4, 100 }), D);
	}

	@Test
	public void identicalValuesAndTies()
	{
		assertEquals(0d, PurchaseStatistics.coefficientOfVariation(new double[] { 30, 30, 30 }), D);
		assertEquals(0d, PurchaseStatistics.theilSenSlope(new double[] { 4, 4, 4, 4 }), D);
		assertEquals(10d, PurchaseStatistics.kthHighest(new double[] { 10, 10, 10, 5 }, 3), D);
	}

	@Test
	public void ewmaWeightsRecentMore()
	{
		final double e = PurchaseStatistics.ewma(new double[] { 10, 10, 10, 20 }, 3);
		assertEquals(true, e > 10d && e < 20d);
		assertEquals(true, e > PurchaseStatistics.mean(new double[] { 10, 10, 10, 20 }) - 3d);
	}

	@Test
	public void theilSenIgnoresOutlierSlope()
	{
		assertEquals(2d, PurchaseStatistics.theilSenSlope(new double[] { 2, 4, 6, 8, 10 }), D);
		assertEquals(1d, PurchaseStatistics.theilSenSlope(new double[] { 1, 2, 3, 4, 100 }), 1.5d);
	}

	@Test
	public void winsorisingCapsOnlyUpperOutliers()
	{
		final double[] w = PurchaseStatistics.winsoriseUpper(new double[] { 10, 11, 10, 12, 11, 500 });
		assertEquals(true, w[5] < 500d);
		assertEquals(10d, w[0], D);
		// fewer than 4 points: untouched
		assertEquals(500d, PurchaseStatistics.winsoriseUpper(new double[] { 10, 500, 11 })[1], D);
	}

	@Test
	public void kthHighestNeedsEnoughValues()
	{
		assertNull(PurchaseStatistics.kthHighest(new double[] { 1, 2, 3 }, 4));
		assertEquals(5d, PurchaseStatistics.kthHighest(new double[] { 9, 8, 7, 5, 4, 3, 2, 1 }, 4), D);
		assertEquals(4d, PurchaseStatistics.kthHighest(new double[] { 9, 8, 7, 5, 4, 3, 2, 1 }, 5), D);
	}
}
