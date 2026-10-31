package com.purchaseoptimizer.facade;

import de.hybris.platform.commercefacades.product.data.ProductData;

import java.util.Collection;
import java.util.List;


public interface OptimizerFacade
{
	/**
	 * Suggested cart for the authenticated B2B customer's unit, ranked, with quantities, confidence and explanation.
	 * Never creates or modifies a cart or order.
	 *
	 * @param userId path user id ("current" or the customer uid); must match the session user
	 * @param requestParameterNames names of all query parameters sent; anything other than budgetTier/fields is rejected
	 *        (the dealer can never send an amount, boost or campaign)
	 * @param budgetTier USUAL (default) or HIGH; HIGH only takes effect if the server derived a high tier
	 */
	List<ProductData> getSuggestedCart(String userId, String budgetTier, Collection<String> requestParameterNames);
}
