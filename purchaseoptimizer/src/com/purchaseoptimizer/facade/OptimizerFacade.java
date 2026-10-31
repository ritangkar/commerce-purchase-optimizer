package com.purchaseoptimizer.facade;

import de.hybris.platform.commercefacades.product.data.ProductData;

import java.util.List;


public interface OptimizerFacade
{
	/**
	 * Suggested cart for the authenticated B2B customer's unit, ranked, with quantities, confidence and explanation.
	 * Never creates or modifies a cart or order.
	 *
	 * @param userId path user id ("current" or the customer uid); must match the session user
	 * @param budgetTier USUAL (default) or HIGH; HIGH only takes effect if the server derived a high tier
	 */
	List<ProductData> getSuggestedCart(String userId, String budgetTier);
}
