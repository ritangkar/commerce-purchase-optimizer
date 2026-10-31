package com.birlaopus.optimizer.jalo;

import de.hybris.platform.jalo.JaloSession;
import de.hybris.platform.jalo.extension.ExtensionManager;

import com.birlaopus.optimizer.constants.BirlaopusoptimizerConstants;


public class BirlaopusoptimizerManager extends GeneratedBirlaopusoptimizerManager
{
	public static final BirlaopusoptimizerManager getInstance()
	{
		final ExtensionManager em = JaloSession.getCurrentSession().getExtensionManager();
		return (BirlaopusoptimizerManager) em.getExtension(BirlaopusoptimizerConstants.EXTENSIONNAME);
	}
}
