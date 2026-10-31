package com.purchaseoptimizer.service;

import com.purchaseoptimizer.data.OptimizerConfigData;


/** Effective tuning: the active OptimizerConfig item (Backoffice), else project.properties defaults. Cached briefly. */
public interface OptimizerConfigService
{
	OptimizerConfigData getConfig();
}
