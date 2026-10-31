package com.purchaseoptimizer.service;

import de.hybris.platform.b2b.model.B2BCustomerModel;
import de.hybris.platform.b2b.model.B2BUnitModel;

import com.purchaseoptimizer.enums.BudgetTier;


/**
 * Builds a suggested cart from the dealer's PRIMARY purchase history (what was ordered from the manufacturer).
 * Nothing here knows or implies dealer stock, sell-out or end-customer demand.
 */
public interface OptimizerService
{
	OptimizerResult generateSuggestedCart(B2BCustomerModel customer, B2BUnitModel unit, BudgetTier requestedTier);
}
