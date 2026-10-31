package com.purchaseoptimizer.facade.impl;

import de.hybris.platform.b2b.model.B2BCustomerModel;
import de.hybris.platform.b2b.model.B2BUnitModel;
import de.hybris.platform.b2b.services.B2BUnitService;
import de.hybris.platform.commercefacades.product.ProductFacade;
import de.hybris.platform.commercefacades.product.ProductOption;
import de.hybris.platform.commercefacades.product.data.ProductData;
import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.user.UserService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.purchaseoptimizer.constants.PurchaseoptimizerConstants;
import com.purchaseoptimizer.enums.BudgetTier;
import com.purchaseoptimizer.exception.OptimizerException;
import com.purchaseoptimizer.facade.OptimizerFacade;
import com.purchaseoptimizer.data.OptimizerResultData;
import com.purchaseoptimizer.data.SuggestionCandidateData;
import com.purchaseoptimizer.service.OptimizerService;


public class DefaultOptimizerFacade implements OptimizerFacade
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultOptimizerFacade.class);
	private static final List<ProductOption> OPTIONS = Arrays.asList(ProductOption.BASIC, ProductOption.PRICE,
			ProductOption.IMAGES);

	@Resource(name = "optimizerService")
	private OptimizerService optimizerService;
	@Resource(name = "userService")
	private UserService userService;
	@Resource(name = "b2bUnitService")
	private B2BUnitService<B2BUnitModel, B2BCustomerModel> b2bUnitService;
	@Resource(name = "productFacade")
	private ProductFacade productFacade;
	@Resource(name = "configurationService")
	private ConfigurationService configurationService;

	@Override
	public List<ProductData> getSuggestedCart(final String userId, final String budgetTier,
			final Collection<String> requestParameterNames)
	{
		rejectUnsupportedParameters(requestParameterNames);
		if (!configurationService.getConfiguration().getBoolean(PurchaseoptimizerConstants.ENABLED, true))
		{
			throw new OptimizerException(OptimizerException.Reason.DISABLED, "Feature is not available");
		}

		final B2BCustomerModel customer = resolveCustomer(userId);
		final B2BUnitModel unit = b2bUnitService.getParent(customer);
		if (unit == null)
		{
			throw new OptimizerException(OptimizerException.Reason.NO_B2B_UNIT, "Customer is not assigned to a B2B unit");
		}

		final BudgetTier tier = parseTier(budgetTier);

		final OptimizerResultData result = optimizerService.generateSuggestedCart(customer, unit, tier);

		final List<ProductData> out = new ArrayList<>();
		for (final SuggestionCandidateData c : result.getLines())
		{
			try
			{
				final ProductData data = productFacade.getProductForCodeAndOptions(c.getProductCode(), OPTIONS);
				data.setSuggestedQuantity(Long.valueOf(c.getSuggestedQuantity()));
				data.setSuggestionReason(c.getExplanation());
				data.setConfidenceTier(c.getConfidenceTier().name());
				data.setSuggestionGroup(c.getGroup().name());
				data.setExplanationSource(c.getExplanationSource());
				data.setEvidenceLabels(new ArrayList<>(c.getEvidenceLabels()));
				out.add(data);
			}
			catch (final RuntimeException e)
			{
				LOG.debug("Skipping suggestion that could not be converted: {}", e.getClass().getSimpleName());
			}
		}
		LOG.info("Suggested cart built: {} lines, aiUsed={}, fallbackUsed={}", out.size(),
				result.isAiUsed(), result.isFallbackUsed());
		return out;
	}

	private static final Set<String> ALLOWED_PARAMETERS = new HashSet<>(Arrays.asList("budgetTier", "fields", "lang", "curr"));

	private void rejectUnsupportedParameters(final Collection<String> names)
	{
		if (names == null)
		{
			return;
		}
		for (final String name : names)
		{
			if (!ALLOWED_PARAMETERS.contains(name))
			{
				// covers budget/amount/boost/campaign style inputs: the server derives all of these itself (lang/curr are OCC standard)
				throw new OptimizerException(OptimizerException.Reason.BAD_REQUEST, "Unsupported parameter");
			}
		}
	}

	private BudgetTier parseTier(final String value)
	{
		if (value == null || value.trim().isEmpty())
		{
			return BudgetTier.USUAL;
		}
		for (final BudgetTier tier : BudgetTier.values())
		{
			if (tier.name().equalsIgnoreCase(value.trim()))
			{
				return tier;
			}
		}
		throw new OptimizerException(OptimizerException.Reason.BAD_REQUEST, "Unsupported budgetTier");
	}

	/** The path user must be the authenticated B2B customer: dealer isolation is enforced here, not by the client. */
	private B2BCustomerModel resolveCustomer(final String userId)
	{
		final UserModel current = userService.getCurrentUser();
		if (current == null || userService.isAnonymousUser(current) || !(current instanceof B2BCustomerModel))
		{
			throw new OptimizerException(OptimizerException.Reason.ACCESS_DENIED, "Access is denied");
		}
		final B2BCustomerModel customer = (B2BCustomerModel) current;
		final boolean isCurrent = "current".equalsIgnoreCase(userId);
		if (!isCurrent && !customer.getUid().equalsIgnoreCase(userId))
		{
			throw new OptimizerException(OptimizerException.Reason.ACCESS_DENIED, "Access is denied");
		}
		return customer;
	}
}
