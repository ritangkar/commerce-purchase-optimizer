package com.purchaseoptimizer.service.impl;

import com.purchaseoptimizer.data.EligibilityData;
import com.purchaseoptimizer.service.OptimizerEligibilityService;

import de.hybris.platform.basecommerce.enums.StockLevelStatus;
import de.hybris.platform.catalog.enums.ArticleApprovalStatus;
import de.hybris.platform.category.CategoryService;
import de.hybris.platform.category.model.CategoryModel;
import de.hybris.platform.commerceservices.price.CommercePriceService;
import de.hybris.platform.commerceservices.stock.CommerceStockService;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.jalo.order.price.PriceInformation;
import de.hybris.platform.product.ProductService;
import de.hybris.platform.servicelayer.exceptions.AmbiguousIdentifierException;
import de.hybris.platform.servicelayer.exceptions.UnknownIdentifierException;
import de.hybris.platform.store.BaseStoreModel;
import de.hybris.platform.store.services.BaseStoreService;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class DefaultOptimizerEligibilityService implements OptimizerEligibilityService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultOptimizerEligibilityService.class);

	@Resource(name = "productService")
	private ProductService productService;
	@Resource(name = "categoryService")
	private CategoryService categoryService;
	@Resource(name = "commercePriceService")
	private CommercePriceService commercePriceService;
	@Resource(name = "commerceStockService")
	private CommerceStockService commerceStockService;
	@Resource(name = "baseStoreService")
	private BaseStoreService baseStoreService;

	@Override
	public EligibilityData check(final String code)
	{
		final ProductModel p = resolve(code);
		if (p == null || !ArticleApprovalStatus.APPROVED.equals(p.getApprovalStatus()))
		{
			return null;
		}
		final Date now = new Date();
		if ((p.getOnlineDate() != null && p.getOnlineDate().after(now))
				|| (p.getOfflineDate() != null && p.getOfflineDate().before(now)))
		{
			return null;
		}
		final EligibilityData ep = new EligibilityData();
		ep.setProductCode(code);
		try
		{
			final PriceInformation pi = commercePriceService.getWebPriceForProduct(p);
			if (pi == null || pi.getPriceValue() == null || pi.getPriceValue().getValue() <= 0d)
			{
				return null;
			}
			ep.setUnitPrice(pi.getPriceValue().getValue());
		}
		catch (final RuntimeException ex)
		{
			return null; // no price for this dealer's price group
		}
		try
		{
			final BaseStoreModel store = baseStoreService.getCurrentBaseStore();
			if (store != null && commerceStockService.isStockSystemEnabled(store) && StockLevelStatus.OUTOFSTOCK
					.equals(commerceStockService.getStockLevelStatusForProductAndBaseStore(p, store)))
			{
				return null;
			}
		}
		catch (final RuntimeException ex)
		{
			LOG.debug("Stock check skipped for a product: {}", ex.getClass().getSimpleName());
		}
		ep.setStep(p.getOrderQuantityInterval() == null || p.getOrderQuantityInterval() < 1 ? 1 : p.getOrderQuantityInterval());
		ep.setMinQty(p.getMinOrderQuantity() == null || p.getMinOrderQuantity() < 1 ? 1 : p.getMinOrderQuantity());
		ep.setMaxQty(p.getMaxOrderQuantity() == null || p.getMaxOrderQuantity() < 1 ? 0 : p.getMaxOrderQuantity());
		ep.setCategoryCodes(new ArrayList<>(categoryCodes(code)));
		return ep;
	}

	@Override
	public Set<String> categoryCodes(final String code)
	{
		final Set<String> out = new LinkedHashSet<>();
		final ProductModel p = resolve(code);
		if (p == null || p.getSupercategories() == null)
		{
			return out;
		}
		for (final CategoryModel c : p.getSupercategories())
		{
			out.add(c.getCode());
			for (final CategoryModel up : categoryService.getAllSupercategoriesForCategory(c))
			{
				out.add(up.getCode());
			}
		}
		return out;
	}

	private ProductModel resolve(final String code)
	{
		try
		{
			return productService.getProductForCode(code);
		}
		catch (final UnknownIdentifierException | AmbiguousIdentifierException e)
		{
			return null;
		}
	}
}
