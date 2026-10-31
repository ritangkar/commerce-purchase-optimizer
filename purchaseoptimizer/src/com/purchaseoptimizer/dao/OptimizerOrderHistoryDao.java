package com.purchaseoptimizer.dao;

import de.hybris.platform.b2b.model.B2BUnitModel;
import de.hybris.platform.core.model.order.OrderEntryModel;

import java.util.Collection;
import java.util.Date;
import java.util.List;


/** Read-only access to a dealer's order history. All queries are fixed and parameterised. */
public interface OptimizerOrderHistoryDao
{
	/**
	 * Order entries of the unit's orders placed since {@code from} whose order status code is in {@code statusCodes}.
	 * Order snapshots (versionID set) are excluded. Sorted by order date, then entry number.
	 */
	List<OrderEntryModel> findOrderEntries(B2BUnitModel unit, Date from, Collection<String> statusCodes);
}
