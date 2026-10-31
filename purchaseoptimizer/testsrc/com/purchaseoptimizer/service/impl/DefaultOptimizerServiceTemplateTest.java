package com.purchaseoptimizer.service.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.purchaseoptimizer.data.SuggestionCandidateData;
import com.purchaseoptimizer.enums.CandidateGroup;
import com.purchaseoptimizer.service.ai.AiResponseValidator;

import de.hybris.bootstrap.annotations.UnitTest;

import org.junit.Test;


/** Every deterministic template must pass the banned-phrase scan, and push items must never read as pattern-based. */
@UnitTest
public class DefaultOptimizerServiceTemplateTest
{
	private final AiResponseValidator validator = new AiResponseValidator();

	private SuggestionCandidateData candidate(final CandidateGroup group, final String dueState)
	{
		final SuggestionCandidateData c = new SuggestionCandidateData();
		c.setGroup(group);
		c.setDueState(dueState);
		c.setOrderCount12m(6);
		c.setDaysSinceLastOrder(21);
		c.setMedianIntervalDays(30);
		c.setSuggestedQuantity(24);
		return c;
	}

	@Test
	public void noTemplateImpliesStockSalesOrDemand()
	{
		for (final CandidateGroup g : CandidateGroup.values())
		{
			for (final String due : new String[] { "APPROACHING_TYPICAL_PERIOD", "BEYOND_TYPICAL_PERIOD", "CATALOGUE_LINK",
					"BUSINESS_PRIORITY" })
			{
				final String text = DefaultOptimizerService.templateFor(candidate(g, due));
				assertFalse(g + "/" + due + ": " + text, validator.containsBannedPhrase(text));
			}
		}
	}

	@Test
	public void strategicAndBundleTextIsNeverPresentedAsPatternBased()
	{
		for (final CandidateGroup g : new CandidateGroup[] { CandidateGroup.STRATEGIC, CandidateGroup.BUNDLE_CAMPAIGN })
		{
			final String text = DefaultOptimizerService.templateFor(candidate(g, "BUSINESS_PRIORITY"));
			assertTrue(text.contains("shown separately from your ordering pattern"));
			assertFalse(text.contains("Based on your previous ordering pattern"));
			assertFalse(text.contains("ordered it"));
		}
	}

	@Test
	public void patternTextCitesOnlyEngineNumbers()
	{
		final String text = DefaultOptimizerService.templateFor(candidate(CandidateGroup.PATTERN, "APPROACHING_TYPICAL_PERIOD"));
		assertTrue(text.contains("6 times") && text.contains("30 days") && text.contains("21 days ago") && text.contains("24"));
	}
}
