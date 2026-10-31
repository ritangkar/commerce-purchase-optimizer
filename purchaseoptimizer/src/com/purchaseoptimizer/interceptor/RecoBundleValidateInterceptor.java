package com.purchaseoptimizer.interceptor;

import com.purchaseoptimizer.enums.RecoStatus;
import com.purchaseoptimizer.model.RecoBundleEntryModel;
import com.purchaseoptimizer.model.RecoBundleModel;

import de.hybris.platform.servicelayer.interceptor.InterceptorContext;
import de.hybris.platform.servicelayer.interceptor.InterceptorException;
import de.hybris.platform.servicelayer.interceptor.ValidateInterceptor;


public class RecoBundleValidateInterceptor extends RecoGovernanceSupport implements ValidateInterceptor<RecoBundleModel>
{
	@Override
	public void onValidate(final RecoBundleModel bundle, final InterceptorContext ctx) throws InterceptorException
	{
		checkWorkflow(bundle, bundle.getStatus(), ctx);
		checkDates(bundle.getValidFrom(), bundle.getValidTo());
		lockApprovedContent(bundle, bundle.getStatus(), ctx, RecoBundleModel.VALIDFROM, RecoBundleModel.VALIDTO,
				RecoBundleModel.PROMOTIONCODE);
		if (bundle.getStatus() != RecoStatus.DRAFT)
		{
			if (bundle.getEntries() == null || bundle.getEntries().isEmpty())
			{
				throw new InterceptorException("A bundle needs at least one entry before it leaves DRAFT");
			}
			for (final RecoBundleEntryModel e : bundle.getEntries())
			{
				if (e.getProduct() == null || e.getQtyRatio() == null || e.getQtyRatio() <= 0d)
				{
					throw new InterceptorException("Every bundle entry needs a product and a qtyRatio > 0");
				}
			}
		}
	}
}
