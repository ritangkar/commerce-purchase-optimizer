package com.purchaseoptimizer.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.purchaseoptimizer.data.OptimizerResultData;
import com.purchaseoptimizer.data.SuggestionCandidateData;
import com.purchaseoptimizer.enums.BudgetTier;
import com.purchaseoptimizer.enums.CandidateGroup;
import com.purchaseoptimizer.enums.ConfidenceTier;
import com.purchaseoptimizer.enums.RecoStatus;
import com.purchaseoptimizer.model.OptimizerSuggestionAuditModel;
import com.purchaseoptimizer.service.OptimizerService;

import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.b2b.model.B2BCustomerModel;
import de.hybris.platform.b2b.model.B2BUnitModel;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;

import java.util.List;
import java.util.Optional;

import javax.annotation.Resource;

import org.junit.Before;
import org.junit.Test;


/**
 * End-to-end pipeline on real order history, prices, products and campaigns. AI is off (default), so explanations are
 * the deterministic templates. NOT executed in the authoring environment.
 */
@IntegrationTest
public class OptimizerServiceIntegrationTest extends AbstractOptimizerIntegrationTest
{
	@Resource(name = "optimizerService")
	private OptimizerService optimizerService;
	@Resource
	private FlexibleSearchService flexibleSearchService;

	private ProductModel owned; // ordered regularly by the dealer, in CAT_PRIMER
	private ProductModel push; // the product the business wants to push, also in CAT_PRIMER
	private ProductModel unrelated; // different category (no category at all)

	@Before
	public void setUpCatalog()
	{
		owned = newProduct("OWNED", 100, 6, true);
		push = newProduct("PUSH", 100, 6, true);
		unrelated = newProduct("UNREL", 100, 6, false);
		// 5 orders, every 30 days, last one 31 days ago -> approaching/at the typical reorder period
		orderRepeatedly(unit, customer, owned, 5, 30, 31, 12);

		// Large past orders so the dealer's usual order size (median order value) comfortably fits several lines.
		// FILLER has no price row, so it is never eligible and never appears in a result.
		final ProductModel filler = modelService.create(ProductModel.class);
		filler.setCode("FILLER");
		filler.setCatalogVersion(catalogVersion);
		modelService.save(filler);
		for (int i = 0; i < 6; i++)
		{
			newOrder(unit, customer, filler, 1000, 100 + i * 17, OrderStatus.COMPLETED);
		}
	}

	private OptimizerResultData run()
	{
		return optimizerService.generateSuggestedCart(customer, unit, BudgetTier.USUAL);
	}

	private Optional<SuggestionCandidateData> line(final OptimizerResultData r, final String code)
	{
		return r.getLines().stream().filter(l -> l.getProductCode().equals(code)).findFirst();
	}

	// ------------------------------------------------------------------ pattern engine
	@Test
	public void dueProductIsSuggestedAsPatternWithEvidenceAndPackAlignedQuantity()
	{
		final SuggestionCandidateData c = line(run(), "OWNED").get();
		assertEquals(CandidateGroup.PATTERN, c.getGroup());
		assertEquals(ConfidenceTier.HIGH, c.getConfidenceTier()); // 5 orders, regular interval
		assertEquals(0, c.getSuggestedQuantity() % 6);
		assertEquals(5, c.getOrderCount12m());
		assertTrue(c.getExplanation().startsWith("Based on your previous ordering pattern"));
	}

	@Test
	public void openPurchaseOrderSuppressesTheProduct()
	{
		newOrder(unit, customer, owned, 12, 3, OrderStatus.CREATED); // ordered, not completed
		assertFalse(line(run(), "OWNED").isPresent());
	}

	@Test
	public void cancelledOrdersDoNotCountAsHistory()
	{
		final ProductModel sparse = newProduct("SPARSE", 100, 6, true);
		orderRepeatedly(unit, customer, sparse, 2, 30, 31, 12); // only 2 completed orders -> below minimum
		for (int i = 0; i < 3; i++)
		{
			newOrder(unit, customer, sparse, 12, 40 + i * 30, OrderStatus.CANCELLED);
		}
		assertFalse(line(run(), "SPARSE").isPresent());
	}

	@Test
	public void productNotDueYetIsNotSuggested()
	{
		final ProductModel recent = newProduct("RECENT", 100, 6, true);
		orderRepeatedly(unit, customer, recent, 5, 30, 2, 12); // last ordered 2 days ago, interval 30
		assertFalse(line(run(), "RECENT").isPresent());
	}

	@Test
	public void anotherDealersOrdersNeverLeakIn()
	{
		final B2BUnitModel otherUnit = newUnit("dealer-unit-B");
		final B2BCustomerModel other = newCustomer("dealer-b@example.com", otherUnit);
		final ProductModel theirs = newProduct("THEIRS", 100, 6, true);
		orderRepeatedly(otherUnit, other, theirs, 5, 30, 31, 12);
		assertFalse(line(run(), "THEIRS").isPresent());
	}

	@Test
	public void noPriceMeansNotSuggested()
	{
		final ProductModel unpriced = modelService.create(ProductModel.class);
		unpriced.setCode("UNPRICED");
		unpriced.setCatalogVersion(catalogVersion);
		unpriced.setApprovalStatus(de.hybris.platform.catalog.enums.ArticleApprovalStatus.APPROVED);
		modelService.save(unpriced);
		orderRepeatedly(unit, customer, unpriced, 5, 30, 31, 12);
		assertFalse(line(run(), "UNPRICED").isPresent());
	}

	// ------------------------------------------------------------------ business configuration
	private void activeCampaignPushing(final ProductModel target, final int holdoutPercent, final double boost)
	{
		withoutOptimizerInterceptors(() -> newCampaign("INT_CAMP", RecoStatus.ACTIVE, holdoutPercent,
				newPriority("INT_PRIO", target, boost, RecoStatus.ACTIVE), null));
	}

	@Test
	public void activeCampaignAddsStrategicLineSeparatelyLabelled()
	{
		// enough pattern lines that the 25 % cap leaves room for one strategic line
		for (int i = 0; i < 3; i++)
		{
			orderRepeatedly(unit, customer, newProduct("EXTRA" + i, 100, 6, true), 5, 30, 31, 12);
		}
		activeCampaignPushing(push, 0, 0.30);

		final OptimizerResultData r = run();
		final SuggestionCandidateData c = line(r, "PUSH").get();
		assertEquals(CandidateGroup.STRATEGIC, c.getGroup());
		assertEquals(ConfidenceTier.EXPLORATORY, c.getConfidenceTier());
		assertTrue(c.getBoostApplied() <= 0.15 + 1e-9); // default boost cap, not the 0.30 configured on the priority
		assertFalse(c.getExplanation().toLowerCase().contains("ordering pattern, you may"));
		assertTrue(r.getStrategyTrace().stream().anyMatch(t -> "INT_CAMP".equals(t.getCampaignCode())));
	}

	@Test
	public void relevanceGateBlocksProductWithoutRelatedHistory()
	{
		activeCampaignPushing(unrelated, 0, 0.30);
		final OptimizerResultData r = run();
		assertFalse(line(r, "UNREL").isPresent());
		assertTrue(r.getStrategyTrace().stream().anyMatch(t -> t.getOutcome().startsWith("GATE_CLOSED")));
	}

	@Test
	public void holdoutDealerGetsNoStrategicLineButItIsAudited()
	{
		activeCampaignPushing(push, 100, 0.30);
		final OptimizerResultData r = run();
		assertFalse(line(r, "PUSH").isPresent());
		assertTrue(r.getStrategyTrace().stream().anyMatch(t -> t.isHoldout()));

		final OptimizerSuggestionAuditModel audit = audit(r.getAuditId());
		assertTrue(audit.getAppliedStrategyJson().contains("\"holdout\":true"));
		assertNotNull(audit.getHoldoutKeyId());
	}

	@Test
	public void draftOrExpiredCampaignIsNeverUsed()
	{
		withoutOptimizerInterceptors(() -> newCampaign("INT_DRAFT", RecoStatus.DRAFT, 0,
				newPriority("INT_PRIO_D", push, 0.1, RecoStatus.ACTIVE), null));
		withoutOptimizerInterceptors(() -> newCampaign("INT_EXP", RecoStatus.EXPIRED, 0,
				newPriority("INT_PRIO_E", push, 0.1, RecoStatus.ACTIVE), null));
		assertFalse(line(run(), "PUSH").isPresent());
	}

	@Test
	public void strategicLineCapIsEnforced()
	{
		// only one pattern line: 1 strategic of 2 lines = 50 % > 25 % cap -> strategic line must be dropped
		activeCampaignPushing(push, 0, 0.10);
		final OptimizerResultData r = run();
		assertFalse(line(r, "PUSH").isPresent());
		assertTrue(line(r, "OWNED").isPresent()); // pattern lines are never removed by the cap
	}

	// ------------------------------------------------------------------ audit and safety
	private OptimizerSuggestionAuditModel audit(final String auditId)
	{
		final FlexibleSearchQuery q = new FlexibleSearchQuery("SELECT {pk} FROM {OptimizerSuggestionAudit} WHERE {auditId} = ?id");
		q.addQueryParameter("id", auditId);
		final List<OptimizerSuggestionAuditModel> rows = flexibleSearchService.<OptimizerSuggestionAuditModel> search(q)
				.getResult();
		assertEquals(1, rows.size());
		return rows.get(0);
	}

	@Test
	public void everyCallWritesAnAuditRecordWithoutPricesOrPrompts()
	{
		final OptimizerResultData r = run();
		final OptimizerSuggestionAuditModel a = audit(r.getAuditId());
		assertEquals(unit, a.getB2bUnit());
		assertEquals(customer, a.getCustomer());
		assertFalse(a.getAiUsed());
		assertTrue(a.getSelectionJson().contains("OWNED"));
		assertFalse(a.getSelectionJson().toLowerCase().contains("price"));
	}
}
