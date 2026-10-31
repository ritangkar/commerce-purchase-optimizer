package com.purchaseoptimizer.service.impl;

import com.purchaseoptimizer.dao.OptimizerStrategyDao;
import com.purchaseoptimizer.data.OptimizerConfigData;
import com.purchaseoptimizer.model.OptimizerConfigModel;
import com.purchaseoptimizer.service.OptimizerConfigService;

import de.hybris.platform.servicelayer.config.ConfigurationService;

import java.util.Date;
import java.util.List;

import javax.annotation.Resource;


public class DefaultOptimizerConfigService implements OptimizerConfigService
{
	private static final String P = "purchaseoptimizer.";

	@Resource(name = "optimizerStrategyDao")
	private OptimizerStrategyDao strategyDao;
	@Resource(name = "configurationService")
	private ConfigurationService configurationService;

	private volatile OptimizerConfigData cached;
	private volatile long cachedAt;

	@Override
	public OptimizerConfigData getConfig()
	{
		final long ttl = configurationService.getConfiguration().getInt(P + "config.cache.seconds", 60) * 1000L;
		final OptimizerConfigData c = cached;
		if (c != null && System.currentTimeMillis() - cachedAt < ttl)
		{
			return c;
		}
		final OptimizerConfigData fresh = load();
		cached = fresh;
		cachedAt = System.currentTimeMillis();
		return fresh;
	}

	private OptimizerConfigData load()
	{
		final OptimizerConfigData d = new OptimizerConfigData();
		final org.apache.commons.configuration.Configuration cfg = configurationService.getConfiguration();
		d.setVersion(0);
		d.setBoostCap(cfg.getDouble(P + "config.boost.cap", 0.15d));
		d.setStrategicLineShareCap(cfg.getDouble(P + "config.strategic.line.cap", 0.25d));
		d.setStrategicValueShareCap(cfg.getDouble(P + "config.strategic.value.cap", 0.30d));
		d.setGateMinCategoryOrders(cfg.getInt(P + "config.gate.min.category.orders", 1));
		d.setMinOrders(cfg.getInt(P + "min.orders", 3));
		d.setDueMin(cfg.getDouble(P + "due.min", 0.85d));
		d.setDueMax(cfg.getDouble(P + "due.max", 1.30d));

		final List<OptimizerConfigModel> rows = strategyDao.findActiveConfigs(new Date());
		if (!rows.isEmpty())
		{
			final OptimizerConfigModel m = rows.get(0); // highest version
			d.setVersion(m.getVersion() == null ? 1 : m.getVersion());
			d.setBoostCap(clamp(m.getBoostCap(), 0d, 1d, d.getBoostCap()));
			d.setStrategicLineShareCap(clamp(m.getStrategicLineShareCap(), 0d, 1d, d.getStrategicLineShareCap()));
			d.setStrategicValueShareCap(clamp(m.getStrategicValueShareCap(), 0d, 1d, d.getStrategicValueShareCap()));
			d.setGateMinCategoryOrders(m.getGateMinCategoryOrders() == null ? d.getGateMinCategoryOrders()
					: Math.max(1, m.getGateMinCategoryOrders()));
			d.setMinOrders(m.getMinOrders() == null ? d.getMinOrders() : Math.max(1, m.getMinOrders()));
			d.setDueMin(clamp(m.getDueMin(), 0.1d, 1d, d.getDueMin()));
			d.setDueMax(clamp(m.getDueMax(), 1d, 5d, d.getDueMax()));
		}
		return d;
	}

	private double clamp(final Double v, final double lo, final double hi, final double fallback)
	{
		return v == null ? fallback : Math.max(lo, Math.min(hi, v));
	}
}
