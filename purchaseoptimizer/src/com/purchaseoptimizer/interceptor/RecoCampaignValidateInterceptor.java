package com.purchaseoptimizer.interceptor;

import com.purchaseoptimizer.enums.RecoStatus;
import com.purchaseoptimizer.model.RecoCampaignModel;

import de.hybris.platform.servicelayer.interceptor.InterceptorContext;
import de.hybris.platform.servicelayer.interceptor.InterceptorException;
import de.hybris.platform.servicelayer.interceptor.ValidateInterceptor;


/** Maker-checker: createdBy must differ from approvedBy for every status beyond IN_REVIEW. */
public class RecoCampaignValidateInterceptor extends RecoGovernanceSupport implements ValidateInterceptor<RecoCampaignModel>
{
	@Override
	public void onValidate(final RecoCampaignModel campaign, final InterceptorContext ctx) throws InterceptorException
	{
		checkWorkflow(campaign, campaign.getStatus(), ctx);
		checkDates(campaign.getValidFrom(), campaign.getValidTo());
		lockApprovedContent(campaign, campaign.getStatus(), ctx, RecoCampaignModel.VALIDFROM, RecoCampaignModel.VALIDTO,
				RecoCampaignModel.SEGMENTS, RecoCampaignModel.REGIONS, RecoCampaignModel.PRIORITIES, RecoCampaignModel.BUNDLES,
				RecoCampaignModel.HOLDOUTPERCENT);

		final Integer holdout = campaign.getHoldoutPercent();
		if (holdout != null && (holdout < 0 || holdout > 100))
		{
			throw new InterceptorException("holdoutPercent must be between 0 and 100");
		}
		if (!ctx.isNew(campaign) && ctx.isModified(campaign, RecoCampaignModel.CREATEDBY))
		{
			throw new InterceptorException("createdBy cannot be changed");
		}

		final RecoStatus s = campaign.getStatus();
		if (s == RecoStatus.APPROVED || s == RecoStatus.ACTIVE || s == RecoStatus.EXPIRED)
		{
			if (campaign.getApprovedBy() != null && campaign.getApprovedBy().equals(campaign.getCreatedBy()))
			{
				throw new InterceptorException("Maker-checker: createdBy and approvedBy must be different users");
			}
			if (campaign.getApprovedBy() == null && s == RecoStatus.APPROVED)
			{
				throw new InterceptorException("approvedBy is required to approve");
			}
		}
	}
}
