package com.purchaseoptimizer.dao.impl;

import com.purchaseoptimizer.dao.OptimizerStrategyDao;
import com.purchaseoptimizer.enums.RecoStatus;
import com.purchaseoptimizer.model.DealerSegmentModel;
import com.purchaseoptimizer.model.OptimizerConfigModel;
import com.purchaseoptimizer.model.RecoCampaignModel;

import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.annotation.Resource;


public class DefaultOptimizerStrategyDao implements OptimizerStrategyDao
{
	private static final String CONFIG_QUERY = "SELECT {c:pk} FROM {OptimizerConfig AS c} "
			+ "WHERE {c:active} = ?active AND ({c:activeFrom} IS NULL OR {c:activeFrom} <= ?now) "
			+ "ORDER BY {c:version} DESC, {c:code} ASC";

	private static final String SEGMENT_QUERY = "SELECT {s:pk} FROM {DealerSegment AS s} ORDER BY {s:code} ASC";

	private static final String CAMPAIGN_QUERY = "SELECT {c:pk} FROM {RecoCampaign AS c} WHERE {c:status} IN (?statuses) "
			+ "ORDER BY {c:code} ASC";

	@Resource(name = "flexibleSearchService")
	private FlexibleSearchService flexibleSearchService;

	@Override
	public List<OptimizerConfigModel> findActiveConfigs(final Date now)
	{
		final FlexibleSearchQuery q = new FlexibleSearchQuery(CONFIG_QUERY);
		q.addQueryParameter("active", Boolean.TRUE);
		q.addQueryParameter("now", now);
		return flexibleSearchService.<OptimizerConfigModel> search(q).getResult();
	}

	@Override
	public List<DealerSegmentModel> findAllSegments()
	{
		return flexibleSearchService.<DealerSegmentModel> search(new FlexibleSearchQuery(SEGMENT_QUERY)).getResult();
	}

	@Override
	public List<RecoCampaignModel> findCampaignsByStatus(final Collection<RecoStatus> statuses)
	{
		if (statuses == null || statuses.isEmpty())
		{
			return Collections.emptyList();
		}
		final FlexibleSearchQuery q = new FlexibleSearchQuery(CAMPAIGN_QUERY);
		q.addQueryParameter("statuses", statuses);
		return flexibleSearchService.<RecoCampaignModel> search(q).getResult();
	}
}
