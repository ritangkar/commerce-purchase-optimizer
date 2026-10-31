package com.purchaseoptimizer.job;

import com.purchaseoptimizer.service.OptimizerStrategyService;

import de.hybris.platform.cronjob.enums.CronJobResult;
import de.hybris.platform.cronjob.enums.CronJobStatus;
import de.hybris.platform.cronjob.model.CronJobModel;
import de.hybris.platform.servicelayer.cronjob.AbstractJobPerformable;
import de.hybris.platform.servicelayer.cronjob.PerformResult;

import java.util.Date;

import javax.annotation.Resource;


/** Activates APPROVED campaigns when their validFrom is reached and expires them after validTo. */
public class CampaignLifecycleJob extends AbstractJobPerformable<CronJobModel>
{
	@Resource(name = "optimizerStrategyService")
	private OptimizerStrategyService strategyService;

	@Override
	public PerformResult perform(final CronJobModel cronJob)
	{
		try
		{
			strategyService.runLifecycle(new Date());
			return new PerformResult(CronJobResult.SUCCESS, CronJobStatus.FINISHED);
		}
		catch (final RuntimeException e)
		{
			return new PerformResult(CronJobResult.ERROR, CronJobStatus.FINISHED);
		}
	}
}
