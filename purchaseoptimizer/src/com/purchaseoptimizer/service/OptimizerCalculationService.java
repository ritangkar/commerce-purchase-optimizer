package com.purchaseoptimizer.service;

import com.purchaseoptimizer.data.SuggestionCandidateData;
import com.purchaseoptimizer.enums.ConfidenceTier;

import java.util.List;
import java.util.Map;


/**
 * Pure, deterministic calculations (statistics, due-ness, quantity, confidence, basket selection).
 * No platform access; every result describes ordering patterns derived from primary order history only.
 */
public interface OptimizerCalculationService
{
	// ---- statistics (series are oldest -> newest where order matters)
	double mean(double[] values);

	double median(double[] values);

	/** Linear-interpolated percentile, p in [0,1]. */
	double percentile(double[] values, double p);

	double medianAbsoluteDeviation(double[] values);

	/** Population coefficient of variation; 0 when n &lt; 2 or mean is 0. */
	double coefficientOfVariation(double[] values);

	/** Weight of an observation halves every {@code halfLifeOrders} observations. */
	double ewma(double[] values, double halfLifeOrders);

	double theilSenSlope(double[] values);

	/** Caps values above Q3 + 1.5*IQR (needs at least 4 values). */
	double[] winsoriseUpper(double[] values);

	/** k-th highest value (1-based, ties count separately); null when fewer than k values. */
	Double kthHighest(double[] values, int k);

	// ---- replenishment / quantity / confidence
	double dueRatio(double daysSinceLastOrder, double medianIntervalDays);

	double dueScore(double ratio, double dueMin, double dueMax, double dropAbove);

	double baseQuantity(double medianQty, double ewmaQty, double medianWeight, double ewmaWeight);

	double trendFactor(double slopePerOrder, int orders, double base, double min, double max);

	/** Rounds to pack multiple, applies MOQ, historical cap and product maximum; 0 if nothing sensible remains. */
	long roundQuantity(double raw, long step, long minQty, double historicalCap, long maxQty);

	double recencyScore(double daysSinceLastOrder);

	double relevance(double due, double mix, double affinity, double recency, double wDue, double wMix, double wAffinity,
			double wRecency);

	ConfidenceTier confidenceTier(int ordersInWindow, double intervalCv, double daysSinceLast, double medianIntervalDays);

	/** sourceWeight: individual history 1.0, catalogue/peer 0.6, strategic-only 0.3. */
	double evidenceScore(int orders, double recency, double intervalCv, double sourceWeight);

	// ---- basket selection
	/**
	 * Multiple-choice bounded knapsack over currency buckets. Costs are rounded up so the budget is never exceeded.
	 *
	 * @param budget null or &lt;= 0 means no limit (every candidate at its suggested quantity)
	 * @return candidateId -> packs chosen (only candidates with at least one pack), in candidateId order
	 */
	Map<String, Long> selectWithinBudget(List<SuggestionCandidateData> candidates, Double budget, double granularity,
			double diminishingFactor);
}
