package com.purchaseoptimizer.dao.impl;

import de.hybris.platform.b2b.model.B2BUnitModel;
import de.hybris.platform.core.model.order.OrderEntryModel;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.search.SearchResult;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import com.purchaseoptimizer.dao.OptimizerOrderHistoryDao;


public class DefaultOptimizerOrderHistoryDao implements OptimizerOrderHistoryDao
{
	private static final String ENTRIES_QUERY = "SELECT {e:pk} FROM {OrderEntry AS e "
			+ "JOIN Order AS o ON {e:order} = {o:pk} "
			+ "JOIN OrderStatus AS s ON {o:status} = {s:pk}} "
			+ "WHERE {o:unit} = ?unit AND {o:date} >= ?fromDate AND {s:code} IN (?statusCodes) "
			+ "AND {o:versionID} IS NULL "
			+ "ORDER BY {o:date} ASC, {e:entryNumber} ASC";

	private static final String RETURNS_QUERY = "SELECT {re:orderEntry}, {re:expectedQuantity} FROM {ReturnEntry AS re "
			+ "JOIN ReturnRequest AS rr ON {re:returnRequest} = {rr:pk} "
			+ "JOIN Order AS o ON {rr:order} = {o:pk}} "
			+ "WHERE {o:unit} = ?unit AND {o:date} >= ?fromDate AND {o:versionID} IS NULL";

	@Resource(name = "flexibleSearchService")
	private FlexibleSearchService flexibleSearchService;

	@Override
	public List<OrderEntryModel> findOrderEntries(final B2BUnitModel unit, final Date from, final Collection<String> statusCodes)
	{
		if (unit == null || from == null || statusCodes == null || statusCodes.isEmpty())
		{
			return Collections.emptyList();
		}
		final FlexibleSearchQuery query = new FlexibleSearchQuery(ENTRIES_QUERY);
		query.addQueryParameter("unit", unit);
		query.addQueryParameter("fromDate", from);
		query.addQueryParameter("statusCodes", statusCodes);
		final SearchResult<OrderEntryModel> result = flexibleSearchService.search(query);
		return result.getResult();
	}

	@Override
	public Map<OrderEntryModel, Long> findReturnedQuantities(final B2BUnitModel unit, final Date from)
	{
		if (unit == null || from == null)
		{
			return Collections.emptyMap();
		}
		final FlexibleSearchQuery query = new FlexibleSearchQuery(RETURNS_QUERY);
		query.addQueryParameter("unit", unit);
		query.addQueryParameter("fromDate", from);
		query.setResultClassList(Arrays.asList(OrderEntryModel.class, Long.class));
		final SearchResult<List<Object>> result = flexibleSearchService.search(query);
		final Map<OrderEntryModel, Long> returned = new HashMap<>();
		for (final List<Object> row : result.getResult())
		{
			if (row.get(0) != null && row.get(1) != null)
			{
				returned.merge((OrderEntryModel) row.get(0), (Long) row.get(1), Long::sum);
			}
		}
		return returned;
	}
}
