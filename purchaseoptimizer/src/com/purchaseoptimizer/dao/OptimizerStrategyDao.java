package com.purchaseoptimizer.dao;

import com.purchaseoptimizer.model.DealerSegmentModel;
import com.purchaseoptimizer.model.OptimizerConfigModel;
import com.purchaseoptimizer.model.RecoCampaignModel;
import com.purchaseoptimizer.enums.RecoStatus;

import java.util.Collection;
import java.util.Date;
import java.util.List;


/** Read access to the business configuration. All queries are fixed and parameterised. */
public interface OptimizerStrategyDao
{
	/** Active config rows whose activeFrom is null or in the past; highest version first. */
	List<OptimizerConfigModel> findActiveConfigs(Date now);

	List<DealerSegmentModel> findAllSegments();

	/** Campaigns in the given statuses (validity dates are checked by the service). */
	List<RecoCampaignModel> findCampaignsByStatus(Collection<RecoStatus> statuses);
}
