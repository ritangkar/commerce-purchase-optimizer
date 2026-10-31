package com.purchaseoptimizer.interceptor;

import com.purchaseoptimizer.enums.RecoStatus;
import com.purchaseoptimizer.model.RecoCampaignModel;

import de.hybris.platform.servicelayer.interceptor.InterceptorContext;
import de.hybris.platform.servicelayer.interceptor.InterceptorException;
import de.hybris.platform.servicelayer.interceptor.PrepareInterceptor;
import de.hybris.platform.servicelayer.user.UserService;

import javax.annotation.Resource;


/**
 * Stamps maker and checker from the session so they cannot be forged: createdBy on creation, approvedBy on the
 * move to APPROVED.
 */
public class RecoCampaignPrepareInterceptor implements PrepareInterceptor<RecoCampaignModel>
{
	@Resource(name = "userService")
	private UserService userService;

	@Override
	public void onPrepare(final RecoCampaignModel campaign, final InterceptorContext ctx) throws InterceptorException
	{
		if (ctx.isNew(campaign))
		{
			campaign.setCreatedBy(userService.getCurrentUser());
			campaign.setApprovedBy(null);
		}
		else if (ctx.isModified(campaign, RecoCampaignModel.STATUS) && RecoStatus.APPROVED.equals(campaign.getStatus()))
		{
			campaign.setApprovedBy(userService.getCurrentUser());
		}
	}
}
