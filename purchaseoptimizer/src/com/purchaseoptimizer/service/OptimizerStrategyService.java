package com.purchaseoptimizer.service;

import com.purchaseoptimizer.data.DealerProfileData;
import com.purchaseoptimizer.data.OptimizerConfigData;
import com.purchaseoptimizer.data.OptimizerResultData;
import com.purchaseoptimizer.data.SuggestionCandidateData;

import de.hybris.platform.b2b.model.B2BUnitModel;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Business configuration ("push products"): dealer segmentation, strategic blend over ACTIVE campaigns, holdout,
 * and the date-driven campaign lifecycle. Deterministic; no AI involvement.
 */
public interface OptimizerStrategyService
{
	/** Region and rule-based segments of the dealer unit. */
	DealerProfileData resolveDealerProfile(B2BUnitModel unit, int orderCount12m, Double usualOrderValue);

	/**
	 * Adds strategic and bundle candidates and boosts existing ones, using only ACTIVE, in-date campaigns targeted at
	 * the dealer's segment and region. Dealers in a campaign's holdout get nothing from it (still traced).
	 * Fills {@code result.strategyTrace} and {@code result.holdoutKeyId}.
	 *
	 * @param orderCountByProduct completed orders of each product the dealer bought in the last 12 months
	 * @param medianQtyByProduct median quantity per order of those products
	 * @param excludedProductCodes products with an open PO (never suggested)
	 * @return all candidates (existing ones possibly boosted, plus new STRATEGIC / BUNDLE_CAMPAIGN lines)
	 */
	List<SuggestionCandidateData> blend(DealerProfileData profile, OptimizerConfigData config,
			List<SuggestionCandidateData> existing, Map<String, Integer> orderCountByProduct,
			Map<String, Double> medianQtyByProduct, Set<String> excludedProductCodes, OptimizerResultData result);

	/**
	 * APPROVED campaigns/priorities/bundles inside their dates become ACTIVE; APPROVED/ACTIVE past validTo become EXPIRED.
	 *
	 * @return {activated, expired}
	 */
	int[] runLifecycle(Date now);
}
