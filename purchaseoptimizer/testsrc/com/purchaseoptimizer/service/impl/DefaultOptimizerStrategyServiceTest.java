package com.purchaseoptimizer.service.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.purchaseoptimizer.dao.OptimizerStrategyDao;
import com.purchaseoptimizer.data.DealerProfileData;
import com.purchaseoptimizer.data.EligibilityData;
import com.purchaseoptimizer.data.OptimizerConfigData;
import com.purchaseoptimizer.data.OptimizerResultData;
import com.purchaseoptimizer.data.SuggestionCandidateData;
import com.purchaseoptimizer.enums.CandidateGroup;
import com.purchaseoptimizer.enums.ConfidenceTier;
import com.purchaseoptimizer.enums.RecoStatus;
import com.purchaseoptimizer.model.DealerSegmentModel;
import com.purchaseoptimizer.model.RecoBundleEntryModel;
import com.purchaseoptimizer.model.RecoBundleModel;
import com.purchaseoptimizer.model.RecoCampaignModel;
import com.purchaseoptimizer.model.StrategicPriorityModel;
import com.purchaseoptimizer.service.OptimizerEligibilityService;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.category.CategoryService;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.model.ModelService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.configuration.PropertiesConfiguration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;


/** Strategic blend rules. NOT executed in the authoring environment (needs the platform test classpath). */
@UnitTest
@RunWith(MockitoJUnitRunner.Silent.class)
public class DefaultOptimizerStrategyServiceTest
{
	@Mock
	private OptimizerStrategyDao strategyDao;
	@Mock
	private OptimizerEligibilityService eligibilityService;
	@Mock
	private CategoryService categoryService;
	@Mock
	private ModelService modelService;
	@Mock
	private ConfigurationService configurationService;
	@Spy
	private DefaultOptimizerCalculationService calc = new DefaultOptimizerCalculationService();
	@InjectMocks
	private DefaultOptimizerStrategyService service;

	private final Date past = new Date(System.currentTimeMillis() - 86_400_000L);
	private final Date future = new Date(System.currentTimeMillis() + 86_400_000L);
	private OptimizerConfigData cfg;
	private DealerProfileData profile;
	private final Map<String, Integer> orders = new HashMap<>();
	private final Map<String, Double> qty = new HashMap<>();
	private RecoCampaignModel campaign;
	private StrategicPriorityModel priority;

	@Before
	public void setUp()
	{
		when(configurationService.getConfiguration()).thenReturn(new PropertiesConfiguration());
		cfg = new OptimizerConfigData();
		cfg.setBoostCap(0.15);
		cfg.setGateMinCategoryOrders(1);
		profile = new DealerProfileData();
		profile.setUnitUid("dealer-1");
		profile.setRegion("IN-MH");
		profile.setSegmentCodes(new ArrayList<>(Arrays.asList("SEG_A")));

		// the dealer bought P_OWN (category CAT_PRIMER) 4 times, median quantity 12
		orders.put("P_OWN", 4);
		qty.put("P_OWN", 12d);
		when(eligibilityService.categoryCodes("P_OWN")).thenReturn(new HashSet<>(Arrays.asList("CAT_PRIMER")));

		priority = mock(StrategicPriorityModel.class);
		final ProductModel target = mock(ProductModel.class);
		when(target.getCode()).thenReturn("P_PUSH");
		when(priority.getCode()).thenReturn("PRIO1");
		when(priority.getProduct()).thenReturn(target);
		when(priority.getBoost()).thenReturn(0.9); // way above the cap
		when(priority.getStatus()).thenReturn(RecoStatus.ACTIVE);
		when(priority.getValidFrom()).thenReturn(past);
		when(priority.getValidTo()).thenReturn(future);

		campaign = mock(RecoCampaignModel.class);
		when(campaign.getCode()).thenReturn("CAMP1");
		when(campaign.getStatus()).thenReturn(RecoStatus.ACTIVE);
		when(campaign.getValidFrom()).thenReturn(past);
		when(campaign.getValidTo()).thenReturn(future);
		when(campaign.getHoldoutPercent()).thenReturn(0);
		when(campaign.getPriorities()).thenReturn(new HashSet<>(Arrays.asList(priority)));
		when(strategyDao.findCampaignsByStatus(any())).thenReturn(Arrays.asList(campaign));
	}

	private void eligible(final String code, final String... categories)
	{
		final EligibilityData e = new EligibilityData();
		e.setProductCode(code);
		e.setStep(6);
		e.setMinQty(6);
		e.setUnitPrice(100);
		e.setCategoryCodes(Arrays.asList(categories));
		when(eligibilityService.check(code)).thenReturn(e);
	}

	private List<SuggestionCandidateData> run(final List<SuggestionCandidateData> existing, final OptimizerResultData result)
	{
		return service.blend(profile, cfg, existing, orders, qty, Collections.emptySet(), result);
	}

	@Test
	public void relevantProductIsAddedAsStrategicWithCappedBoost()
	{
		eligible("P_PUSH", "CAT_PRIMER");
		final OptimizerResultData result = new OptimizerResultData();
		final List<SuggestionCandidateData> out = run(new ArrayList<>(), result);
		assertEquals(1, out.size());
		final SuggestionCandidateData c = out.get(0);
		assertEquals(CandidateGroup.STRATEGIC, c.getGroup());
		assertEquals(ConfidenceTier.EXPLORATORY, c.getConfidenceTier());
		assertEquals(0.15, c.getBoostApplied(), 1e-9); // 0.9 capped at boostCap
		assertEquals("PRIO1", c.getStrategicCode());
		assertEquals(12L, c.getSuggestedQuantity()); // typical 12, pack 6
	}

	@Test
	public void gateBlocksProductWithoutRelatedHistory()
	{
		eligible("P_PUSH", "CAT_UNRELATED");
		final OptimizerResultData result = new OptimizerResultData();
		assertTrue(run(new ArrayList<>(), result).isEmpty());
		assertTrue(result.getStrategyTrace().stream().anyMatch(t -> t.getOutcome().startsWith("GATE_CLOSED")));
	}

	@Test
	public void ineligibleProductNeverAppears()
	{
		when(eligibilityService.check("P_PUSH")).thenReturn(null);
		assertTrue(run(new ArrayList<>(), new OptimizerResultData()).isEmpty());
	}

	@Test
	public void holdoutDealerGetsNoBoostButIsTraced()
	{
		eligible("P_PUSH", "CAT_PRIMER");
		when(campaign.getHoldoutPercent()).thenReturn(100);
		final OptimizerResultData result = new OptimizerResultData();
		assertTrue(run(new ArrayList<>(), result).isEmpty());
		assertTrue(result.getStrategyTrace().get(0).isHoldout());
		assertEquals("CAMP1", result.getStrategyTrace().get(0).getCampaignCode());
	}

	@Test
	public void existingPatternLineIsBoostedNotRelabelled()
	{
		eligible("P_PUSH", "CAT_PRIMER");
		final SuggestionCandidateData existing = new SuggestionCandidateData();
		existing.setProductCode("P_PUSH");
		existing.setGroup(CandidateGroup.PATTERN);
		existing.setRelevance(0.40);
		existing.setEvidenceLabels(new ArrayList<>());
		final List<SuggestionCandidateData> out = run(new ArrayList<>(Arrays.asList(existing)), new OptimizerResultData());
		assertEquals(1, out.size());
		assertEquals(CandidateGroup.PATTERN, out.get(0).getGroup());
		assertEquals(0.55, out.get(0).getRelevance(), 1e-9);
	}

	@Test
	public void campaignTargetedAtOtherSegmentOrRegionIsIgnored()
	{
		eligible("P_PUSH", "CAT_PRIMER");
		final DealerSegmentModel other = mock(DealerSegmentModel.class);
		when(other.getCode()).thenReturn("SEG_B");
		when(campaign.getSegments()).thenReturn(new HashSet<>(Arrays.asList(other)));
		assertTrue(run(new ArrayList<>(), new OptimizerResultData()).isEmpty());

		when(campaign.getSegments()).thenReturn(Collections.emptySet());
		when(campaign.getRegions()).thenReturn(new HashSet<>(Arrays.asList("IN-KA")));
		assertTrue(run(new ArrayList<>(), new OptimizerResultData()).isEmpty());

		when(campaign.getRegions()).thenReturn(new HashSet<>(Arrays.asList("in-mh"))); // case-insensitive match
		assertEquals(1, run(new ArrayList<>(), new OptimizerResultData()).size());
	}

	@Test
	public void unusableCampaignOrPriorityIsNeverUsed()
	{
		eligible("P_PUSH", "CAT_PRIMER");
		when(priority.getStatus()).thenReturn(RecoStatus.DRAFT);
		assertTrue(run(new ArrayList<>(), new OptimizerResultData()).isEmpty());

		when(priority.getStatus()).thenReturn(RecoStatus.ACTIVE);
		when(priority.getValidTo()).thenReturn(past); // expired window
		assertTrue(run(new ArrayList<>(), new OptimizerResultData()).isEmpty());

		when(priority.getValidTo()).thenReturn(future);
		when(campaign.getValidTo()).thenReturn(past); // campaign outside its dates
		assertTrue(run(new ArrayList<>(), new OptimizerResultData()).isEmpty());
	}

	private RecoBundleModel bundle(final String... productCodes)
	{
		final RecoBundleModel b = mock(RecoBundleModel.class);
		when(b.getCode()).thenReturn("BUN1");
		when(b.getStatus()).thenReturn(RecoStatus.ACTIVE);
		when(b.getValidFrom()).thenReturn(past);
		when(b.getValidTo()).thenReturn(future);
		final List<RecoBundleEntryModel> entries = new ArrayList<>();
		for (final String code : productCodes)
		{
			final RecoBundleEntryModel e = mock(RecoBundleEntryModel.class);
			final ProductModel p = mock(ProductModel.class);
			when(p.getCode()).thenReturn(code);
			when(e.getProduct()).thenReturn(p);
			when(e.getQtyRatio()).thenReturn(1.0);
			entries.add(e);
		}
		when(b.getEntries()).thenReturn(entries);
		return b;
	}

	@Test
	public void bundleIsAddedWholeOrNotAtAll()
	{
		when(campaign.getPriorities()).thenReturn(Collections.emptySet());
		when(campaign.getBundles()).thenReturn(new HashSet<>(Arrays.asList(bundle("B1", "B2"))));
		eligible("B1", "CAT_PRIMER");
		eligible("B2", "CAT_PRIMER");
		final List<SuggestionCandidateData> out = run(new ArrayList<>(), new OptimizerResultData());
		assertEquals(2, out.size());
		assertTrue(out.stream().allMatch(c -> c.getGroup() == CandidateGroup.BUNDLE_CAMPAIGN && "BUN1".equals(c.getBundleCode())));

		when(eligibilityService.check("B2")).thenReturn(null); // one unusable line drops the whole bundle
		assertTrue(run(new ArrayList<>(), new OptimizerResultData()).isEmpty());
	}

	@Test
	public void lifecycleActivatesAndExpiresByDate()
	{
		final RecoCampaignModel toActivate = mock(RecoCampaignModel.class);
		when(toActivate.getStatus()).thenReturn(RecoStatus.APPROVED);
		when(toActivate.getValidFrom()).thenReturn(past);
		when(toActivate.getValidTo()).thenReturn(future);
		final RecoCampaignModel toExpire = mock(RecoCampaignModel.class);
		when(toExpire.getStatus()).thenReturn(RecoStatus.ACTIVE);
		when(toExpire.getValidFrom()).thenReturn(new Date(0));
		when(toExpire.getValidTo()).thenReturn(past);
		final RecoCampaignModel notYet = mock(RecoCampaignModel.class);
		when(notYet.getStatus()).thenReturn(RecoStatus.APPROVED);
		when(notYet.getValidFrom()).thenReturn(future);
		when(notYet.getValidTo()).thenReturn(new Date(future.getTime() + 1000));
		when(strategyDao.findCampaignsByStatus(any())).thenReturn(Arrays.asList(toActivate, toExpire, notYet));

		final int[] r = service.runLifecycle(new Date());
		assertEquals(1, r[0]);
		assertEquals(1, r[1]);
		verify(toActivate).setStatus(RecoStatus.ACTIVE);
		verify(toExpire).setStatus(RecoStatus.EXPIRED);
		assertFalse(r[0] > 1);
	}
}
