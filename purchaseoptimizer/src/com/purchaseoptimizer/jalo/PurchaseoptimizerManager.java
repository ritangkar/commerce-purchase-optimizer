package com.purchaseoptimizer.jalo;

import de.hybris.platform.jalo.JaloSession;
import de.hybris.platform.jalo.extension.ExtensionManager;

import com.purchaseoptimizer.constants.PurchaseoptimizerConstants;


public class PurchaseoptimizerManager extends GeneratedPurchaseoptimizerManager
{
	public static final PurchaseoptimizerManager getInstance()
	{
		final ExtensionManager em = JaloSession.getCurrentSession().getExtensionManager();
		return (PurchaseoptimizerManager) em.getExtension(PurchaseoptimizerConstants.EXTENSIONNAME);
	}
}
