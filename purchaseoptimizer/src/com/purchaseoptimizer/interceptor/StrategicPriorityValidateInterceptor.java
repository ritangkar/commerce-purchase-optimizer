package com.purchaseoptimizer.interceptor;

import com.purchaseoptimizer.model.StrategicPriorityModel;

import de.hybris.platform.servicelayer.interceptor.InterceptorContext;
import de.hybris.platform.servicelayer.interceptor.InterceptorException;
import de.hybris.platform.servicelayer.interceptor.ValidateInterceptor;


public class StrategicPriorityValidateInterceptor extends RecoGovernanceSupport
		implements ValidateInterceptor<StrategicPriorityModel>
{
	@Override
	public void onValidate(final StrategicPriorityModel p, final InterceptorContext ctx) throws InterceptorException
	{
		checkWorkflow(p, p.getStatus(), ctx);
		checkDates(p.getValidFrom(), p.getValidTo());
		lockApprovedContent(p, p.getStatus(), ctx, StrategicPriorityModel.PRODUCT, StrategicPriorityModel.CATEGORY,
				StrategicPriorityModel.BOOST, StrategicPriorityModel.VALIDFROM, StrategicPriorityModel.VALIDTO,
				StrategicPriorityModel.SEGMENTS, StrategicPriorityModel.REGIONS);
		if ((p.getProduct() == null) == (p.getCategory() == null))
		{
			throw new InterceptorException("Exactly one of product or category must be set");
		}
		final double hardMax = configurationService.getConfiguration().getDouble("purchaseoptimizer.boost.hard.max", 0.5d);
		if (p.getBoost() == null || p.getBoost() < 0d || p.getBoost() > hardMax)
		{
			throw new InterceptorException("boost must be between 0 and " + hardMax);
		}
	}
}
