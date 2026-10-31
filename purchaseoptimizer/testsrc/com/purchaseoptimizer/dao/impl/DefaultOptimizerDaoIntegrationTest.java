package com.purchaseoptimizer.dao.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.purchaseoptimizer.dao.OptimizerOrderHistoryDao;
import com.purchaseoptimizer.dao.OptimizerStrategyDao;
import com.purchaseoptimizer.enums.RecoStatus;
import com.purchaseoptimizer.integration.AbstractOptimizerIntegrationTest;
import com.purchaseoptimizer.model.DealerSegmentModel;
import com.purchaseoptimizer.model.OptimizerConfigModel;
import com.purchaseoptimizer.model.RecoCampaignModel;

import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.b2b.model.B2BCustomerModel;
import de.hybris.platform.b2b.model.B2BUnitModel;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.order.OrderEntryModel;
import de.hybris.platform.core.model.product.ProductModel;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.junit.Test;


/** Real FlexibleSearch against the test system. NOT executed in the authoring environment. */
@IntegrationTest
public class DefaultOptimizerDaoIntegrationTest extends AbstractOptimizerIntegrationTest
{
	@Resource(name = "optimizerOrderHistoryDao")
	private OptimizerOrderHistoryDao orderHistoryDao;
	@Resource(name = "optimizerStrategyDao")
	private OptimizerStrategyDao strategyDao;

	private Date daysAgo(final int d)
	{
		return new Date(System.currentTimeMillis() - d * DAY);
	}

	@Test
	public void orderHistoryIsScopedToTheUnitTheWindowAndTheStatuses()
	{
		final ProductModel p = newProduct("P1", 100, 6, true);
		final B2BUnitModel otherUnit = newUnit("dealer-unit-B");
		final B2BCustomerModel other = newCustomer("dealer-b@example.com", otherUnit);

		newOrder(unit, customer, p, 12, 10, OrderStatus.COMPLETED);
		newOrder(unit, customer, p, 12, 400, OrderStatus.COMPLETED); // outside the window
		newOrder(unit, customer, p, 12, 5, OrderStatus.CANCELLED); // wrong status
		newOrder(otherUnit, other, p, 99, 10, OrderStatus.COMPLETED); // another dealer

		final List<OrderEntryModel> entries = orderHistoryDao.findOrderEntries(unit, daysAgo(365),
				Collections.singletonList("COMPLETED"));

		assertEquals(1, entries.size());
		assertEquals(Long.valueOf(12), entries.get(0).getQuantity());
		assertEquals(unit, ((de.hybris.platform.core.model.order.OrderModel) entries.get(0).getOrder()).getUnit());
	}

	@Test
	public void orderHistoryIsSortedByOrderDateAscending()
	{
		final ProductModel p = newProduct("P2", 100, 6, true);
		newOrder(unit, customer, p, 1, 5, OrderStatus.COMPLETED);
		newOrder(unit, customer, p, 2, 50, OrderStatus.COMPLETED);
		newOrder(unit, customer, p, 3, 20, OrderStatus.COMPLETED);

		final List<Long> qty = orderHistoryDao.findOrderEntries(unit, daysAgo(365), Collections.singletonList("COMPLETED"))
				.stream().map(OrderEntryModel::getQuantity).collect(Collectors.toList());

		assertEquals(Arrays.asList(2L, 3L, 1L), qty);
	}

	@Test
	public void orderHistoryHandlesEmptyInput()
	{
		assertTrue(orderHistoryDao.findOrderEntries(unit, daysAgo(365), Collections.emptyList()).isEmpty());
		assertTrue(orderHistoryDao.findOrderEntries(null, daysAgo(365), Collections.singletonList("COMPLETED")).isEmpty());
	}

	@Test
	public void injectedTextIsTreatedAsDataNotQuery()
	{
		newProduct("P3", 100, 6, true);
		// a hostile "status code" must simply match nothing (parameterised query)
		final List<OrderEntryModel> entries = orderHistoryDao.findOrderEntries(unit, daysAgo(365),
				Collections.singletonList("COMPLETED' OR '1'='1"));
		assertTrue(entries.isEmpty());
	}

	@Test
	public void activeConfigsAreOrderedByVersionAndExcludeInactiveAndFuture()
	{
		withoutOptimizerInterceptors(() -> {
			config("CFG1", 1, true, daysAgo(10));
			config("CFG2", 3, true, daysAgo(1));
			config("CFG3", 9, false, daysAgo(1)); // inactive
			config("CFG4", 7, true, new Date(System.currentTimeMillis() + 10 * DAY)); // not yet active
			config("CFG5", 2, true, null); // no start date = active
		});

		final List<String> codes = strategyDao.findActiveConfigs(new Date()).stream().map(OptimizerConfigModel::getCode)
				.collect(Collectors.toList());

		assertEquals(Arrays.asList("CFG2", "CFG5", "CFG1"), codes);
	}

	private void config(final String code, final int version, final boolean active, final Date from)
	{
		final OptimizerConfigModel c = modelService.create(OptimizerConfigModel.class);
		c.setCode(code);
		c.setVersion(Integer.valueOf(version));
		c.setActive(Boolean.valueOf(active));
		c.setActiveFrom(from);
		modelService.save(c);
	}

	@Test
	public void campaignsAreFilteredByStatus()
	{
		withoutOptimizerInterceptors(() -> {
			newCampaign("C_DRAFT", RecoStatus.DRAFT, 10, null, null);
			newCampaign("C_ACTIVE", RecoStatus.ACTIVE, 10, null, null);
			newCampaign("C_EXPIRED", RecoStatus.EXPIRED, 10, null, null);
		});

		final List<String> active = strategyDao.findCampaignsByStatus(Collections.singletonList(RecoStatus.ACTIVE)).stream()
				.map(RecoCampaignModel::getCode).collect(Collectors.toList());
		assertEquals(Collections.singletonList("C_ACTIVE"), active);

		assertTrue(strategyDao.findCampaignsByStatus(Collections.emptyList()).isEmpty());
		assertFalse(strategyDao.findCampaignsByStatus(Arrays.asList(RecoStatus.DRAFT, RecoStatus.EXPIRED)).isEmpty());
	}

	@Test
	public void segmentsAreReturnedSortedByCode()
	{
		for (final String code : Arrays.asList("SEG_B", "SEG_A"))
		{
			final DealerSegmentModel s = modelService.create(DealerSegmentModel.class);
			s.setCode(code);
			modelService.save(s);
		}
		final List<String> codes = strategyDao.findAllSegments().stream().map(DealerSegmentModel::getCode)
				.collect(Collectors.toList());
		assertTrue(codes.indexOf("SEG_A") < codes.indexOf("SEG_B"));
	}
}
