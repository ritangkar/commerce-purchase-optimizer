package com.purchaseoptimizer.service;

import com.purchaseoptimizer.data.EligibilityData;


/** Decides whether a product may be suggested to the current dealer. Runs BEFORE any scoring. */
public interface OptimizerEligibilityService
{
	/**
	 * @return pack/MOQ/price facts when the product is approved, online, priced for the current user's price group and
	 *         not flagged unavailable by the stock system; null otherwise
	 */
	EligibilityData check(String productCode);

	/** Category codes (direct and all super categories) of a product, empty if unknown. */
	java.util.Set<String> categoryCodes(String productCode);
}
