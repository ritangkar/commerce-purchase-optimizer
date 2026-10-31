package com.purchaseoptimizer.service;

import com.purchaseoptimizer.data.SuggestionCandidateData;
import com.purchaseoptimizer.enums.ConfidenceTier;
import com.purchaseoptimizer.enums.RecoStatus;

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

	// ---- strategic blend (business configuration)
	/** Allowed workflow moves: DRAFT->IN_REVIEW, IN_REVIEW->DRAFT/APPROVED, APPROVED->ACTIVE/EXPIRED/DRAFT, ACTIVE->EXPIRED. */
	boolean isTransitionAllowed(RecoStatus from, RecoStatus to);

	/**
	 * Deterministic holdout: HMAC-SHA256(secret, campaignCode | dealerId) mod 100 &lt; holdoutPercent.
	 * 0 or less = nobody, 100 or more = everybody. The raw dealer id never leaves the JVM.
	 */
	boolean isHoldout(String secret, String campaignCode, String dealerId, int holdoutPercent);

	/** Relevance gate: a boost only applies if the dealer has enough orders in a related category. */
	boolean gateOpen(int ordersInRelatedCategory, int minOrders);

	/** final = relevance + min(boost, boostCap) * gate, never negative. */
	double finalScore(double relevance, double boost, double boostCap, boolean gateOpen);

	/** Bundle line quantity = dealer's typical quantity x qtyRatio, rounded to pack multiple / MOQ / maximum. */
	long scaleBundleQuantity(double typicalQty, double qtyRatio, long step, long minQty, long maxQty);

	/**
	 * Enforces the strategic caps on a selected basket: strategic + bundle lines at most {@code lineCap} of all
	 * lines (rounded down) and {@code valueCap} of cart value. Removes the weakest strategic unit (a whole bundle at
	 * once) until both hold. Pattern/affinity lines are never removed.
	 */
	List<SuggestionCandidateData> enforceStrategicCaps(List<SuggestionCandidateData> selected, double lineCap,
			double valueCap);

	// ---- basket selection
	/**
	 * Multiple-choice bounded knapsack over currency buckets. Costs are rounded up so the budget is never exceeded.
	 *
	 * Candidates sharing a non-empty bundleCode are all-or-nothing: taken together at their suggested quantities or not at all.
	 *
	 * @param budget null or &lt;= 0 means no limit (every candidate at its suggested quantity)
	 * @return candidateId -> packs chosen (only candidates with at least one pack), in candidateId order
	 */
	Map<String, Long> selectWithinBudget(List<SuggestionCandidateData> candidates, Double budget, double granularity,
			double diminishingFactor);
}
