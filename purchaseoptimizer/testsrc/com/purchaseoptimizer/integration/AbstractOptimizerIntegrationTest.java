package com.purchaseoptimizer.integration;

import com.purchaseoptimizer.enums.RecoStatus;
import com.purchaseoptimizer.model.RecoBundleModel;
import com.purchaseoptimizer.model.RecoCampaignModel;
import com.purchaseoptimizer.model.StrategicPriorityModel;

import de.hybris.platform.b2b.model.B2BCustomerModel;
import de.hybris.platform.b2b.model.B2BUnitModel;
import de.hybris.platform.catalog.CatalogVersionService;
import de.hybris.platform.catalog.enums.ArticleApprovalStatus;
import de.hybris.platform.catalog.model.CatalogModel;
import de.hybris.platform.catalog.model.CatalogVersionModel;
import de.hybris.platform.category.model.CategoryModel;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.OrderEntryModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.core.model.product.UnitModel;
import de.hybris.platform.europe1.model.PriceRowModel;
import de.hybris.platform.servicelayer.ServicelayerBaseTest;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.interceptor.impl.InterceptorExecutionPolicy;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.session.SessionExecutionBody;
import de.hybris.platform.servicelayer.session.SessionService;
import de.hybris.platform.servicelayer.user.UserService;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import javax.annotation.Resource;

import org.junit.Before;


/**
 * Fixtures shared by the integration tests: a B2B unit with a customer, a catalog with priced, approved products and
 * helpers to create order history. Runs on the SAP Commerce test system (ServicelayerBaseTest).
 * NOT executed in the authoring environment.
 */
public abstract class AbstractOptimizerIntegrationTest extends ServicelayerBaseTest
{
	protected static final long DAY = 86_400_000L;

	@Resource
	protected ModelService modelService;
	@Resource
	protected UserService userService;
	@Resource
	protected SessionService sessionService;
	@Resource
	protected CatalogVersionService catalogVersionService;
	@Resource
	protected CommonI18NService commonI18NService;

	protected CurrencyModel currency;
	protected UnitModel pieces;
	protected CatalogVersionModel catalogVersion;
	protected CategoryModel category;
	protected B2BUnitModel unit;
	protected B2BCustomerModel customer;

	@Before
	public void baseSetUp()
	{
		currency = modelService.create(CurrencyModel.class);
		currency.setIsocode("INR" + System.nanoTime() % 1000);
		currency.setSymbol("Rs");
		currency.setBase(Boolean.FALSE);
		currency.setActive(Boolean.TRUE);
		currency.setConversion(1d);
		currency.setDigits(2);
		modelService.save(currency);

		pieces = modelService.create(UnitModel.class);
		pieces.setCode("pieces" + System.nanoTime());
		pieces.setUnitType("pieces");
		modelService.save(pieces);

		final CatalogModel catalog = modelService.create(CatalogModel.class);
		catalog.setId("optCatalog" + System.nanoTime());
		modelService.save(catalog);
		catalogVersion = modelService.create(CatalogVersionModel.class);
		catalogVersion.setCatalog(catalog);
		catalogVersion.setVersion("Online");
		catalogVersion.setActive(Boolean.TRUE);
		modelService.save(catalogVersion);
		catalogVersionService.setSessionCatalogVersions(Collections.singletonList(catalogVersion));

		category = modelService.create(CategoryModel.class);
		category.setCode("CAT_PRIMER");
		category.setCatalogVersion(catalogVersion);
		modelService.save(category);

		unit = newUnit("dealer-unit-A");
		customer = newCustomer("dealer-a@example.com", unit);
		commonI18NService.setCurrentCurrency(currency);
		userService.setCurrentUser(customer);
	}

	// ---------------------------------------------------------------- master data
	protected B2BUnitModel newUnit(final String uid)
	{
		final B2BUnitModel u = modelService.create(B2BUnitModel.class);
		u.setUid(uid);
		u.setName(uid);
		u.setLocName(uid);
		modelService.save(u);
		return u;
	}

	protected B2BCustomerModel newCustomer(final String uid, final B2BUnitModel parent)
	{
		final B2BCustomerModel c = modelService.create(B2BCustomerModel.class);
		c.setUid(uid);
		c.setName(uid);
		c.setGroups(new HashSet<>(Arrays.asList(parent)));
		modelService.save(c);
		return c;
	}

	protected ProductModel newProduct(final String code, final double price, final int packSize, final boolean inCategory)
	{
		final ProductModel p = modelService.create(ProductModel.class);
		p.setCode(code);
		p.setCatalogVersion(catalogVersion);
		p.setApprovalStatus(ArticleApprovalStatus.APPROVED);
		p.setUnit(pieces);
		p.setOrderQuantityInterval(Integer.valueOf(packSize));
		if (inCategory)
		{
			p.setSupercategories(Collections.singletonList(category));
		}
		modelService.save(p);

		final PriceRowModel row = modelService.create(PriceRowModel.class);
		row.setProduct(p);
		row.setCatalogVersion(catalogVersion);
		row.setPrice(price);
		row.setCurrency(currency);
		row.setUnit(pieces);
		row.setNet(Boolean.TRUE);
		modelService.save(row);
		return p;
	}

	// ---------------------------------------------------------------- order history
	/** One order of {@code buyer}'s unit with a single entry, placed {@code daysAgo} days ago. */
	protected OrderModel newOrder(final B2BUnitModel orderUnit, final B2BCustomerModel buyer, final ProductModel product,
			final long qty, final int daysAgo, final OrderStatus status)
	{
		final OrderModel o = modelService.create(OrderModel.class);
		o.setCode("ord-" + System.nanoTime());
		o.setUser(buyer);
		o.setUnit(orderUnit);
		o.setCurrency(currency);
		o.setDate(new Date(System.currentTimeMillis() - daysAgo * DAY));
		o.setNet(Boolean.TRUE);
		o.setStatus(status);
		modelService.save(o);

		final OrderEntryModel e = modelService.create(OrderEntryModel.class);
		e.setOrder(o);
		e.setProduct(product);
		e.setQuantity(Long.valueOf(qty));
		e.setUnit(pieces);
		e.setEntryNumber(Integer.valueOf(0));
		e.setBasePrice(100d);
		e.setTotalPrice(100d * qty);
		modelService.save(e);
		modelService.refresh(o);
		return o;
	}

	/** A product ordered {@code times} times, every {@code intervalDays}, most recent {@code lastDaysAgo} days ago. */
	protected void orderRepeatedly(final B2BUnitModel orderUnit, final B2BCustomerModel buyer, final ProductModel product,
			final int times, final int intervalDays, final int lastDaysAgo, final long qty)
	{
		for (int i = 0; i < times; i++)
		{
			newOrder(orderUnit, buyer, product, qty, lastDaysAgo + (times - 1 - i) * intervalDays, OrderStatus.COMPLETED);
		}
	}

	// ---------------------------------------------------------------- business configuration fixtures
	/**
	 * Runs {@code body} with the optimizer interceptors off, to build fixtures in states (ACTIVE...) a user cannot reach
	 * directly. Use only for fixtures; governance tests run with the interceptors on.
	 */
	protected void withoutOptimizerInterceptors(final Runnable body)
	{
		final Map<String, Object> params = new HashMap<>();
		params.put(InterceptorExecutionPolicy.DISABLED_INTERCEPTOR_BEANS, new HashSet<>(Arrays.asList(
				"recoCampaignPrepareInterceptor", "recoCampaignValidateInterceptor", "strategicPriorityValidateInterceptor",
				"recoBundleValidateInterceptor")));
		sessionService.executeInLocalViewWithParams(params, new SessionExecutionBody()
		{
			@Override
			public void executeWithoutResult()
			{
				body.run();
			}
		});
	}

	protected StrategicPriorityModel newPriority(final String code, final ProductModel target, final double boost,
			final RecoStatus status)
	{
		final StrategicPriorityModel p = modelService.create(StrategicPriorityModel.class);
		p.setCode(code);
		p.setProduct(target);
		p.setBoost(boost);
		p.setStatus(status);
		p.setValidFrom(new Date(System.currentTimeMillis() - DAY));
		p.setValidTo(new Date(System.currentTimeMillis() + DAY));
		modelService.save(p);
		return p;
	}

	protected RecoCampaignModel newCampaign(final String code, final RecoStatus status, final int holdoutPercent,
			final StrategicPriorityModel priority, final RecoBundleModel bundle)
	{
		final RecoCampaignModel c = modelService.create(RecoCampaignModel.class);
		c.setCode(code);
		c.setStatus(status);
		c.setHoldoutPercent(Integer.valueOf(holdoutPercent));
		c.setValidFrom(new Date(System.currentTimeMillis() - DAY));
		c.setValidTo(new Date(System.currentTimeMillis() + DAY));
		if (priority != null)
		{
			c.setPriorities(new HashSet<>(Arrays.asList(priority)));
		}
		if (bundle != null)
		{
			c.setBundles(new HashSet<>(Arrays.asList(bundle)));
		}
		modelService.save(c);
		return c;
	}
}
