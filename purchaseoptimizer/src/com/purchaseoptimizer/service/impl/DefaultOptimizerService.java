package com.purchaseoptimizer.service.impl;

import de.hybris.platform.b2b.model.B2BCustomerModel;
import de.hybris.platform.b2b.model.B2BUnitModel;
import de.hybris.platform.catalog.enums.ArticleApprovalStatus;
import de.hybris.platform.category.model.CategoryModel;
import de.hybris.platform.commerceservices.price.CommercePriceService;
import de.hybris.platform.commerceservices.stock.CommerceStockService;
import de.hybris.platform.core.model.order.OrderEntryModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.basecommerce.enums.StockLevelStatus;
import de.hybris.platform.catalog.model.ProductReferenceModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.exceptions.AmbiguousIdentifierException;
import de.hybris.platform.servicelayer.exceptions.UnknownIdentifierException;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.store.BaseStoreModel;
import de.hybris.platform.store.services.BaseStoreService;
import de.hybris.platform.jalo.order.price.PriceInformation;
import de.hybris.platform.product.ProductService;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.purchaseoptimizer.dao.OptimizerOrderHistoryDao;
import com.purchaseoptimizer.enums.BudgetTier;
import com.purchaseoptimizer.enums.CandidateGroup;
import com.purchaseoptimizer.enums.ConfidenceTier;
import com.purchaseoptimizer.data.OptimizerResultData;
import com.purchaseoptimizer.data.SuggestionCandidateData;
import com.purchaseoptimizer.model.OptimizerSuggestionAuditModel;
import com.purchaseoptimizer.service.OptimizerCalculationService;
import com.purchaseoptimizer.service.OptimizerService;
import com.purchaseoptimizer.service.ai.AiResponseValidator;
import com.purchaseoptimizer.service.ai.OpenAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;


/**
 * Pipeline: history (hygiene) -> per-product statistics -> due/quantity/confidence -> eligibility ->
 * score -> automatic order-size profile -> knapsack -> explanations (OpenAI, template fallback) -> audit.
 */
public class DefaultOptimizerService implements OptimizerService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultOptimizerService.class);
	private static final double MS_PER_DAY = 86_400_000d;
	private static final String P = "purchaseoptimizer.";

	@Resource(name = "optimizerOrderHistoryDao")
	private OptimizerOrderHistoryDao orderHistoryDao;
	@Resource(name = "configurationService")
	private ConfigurationService configurationService;
	@Resource(name = "productService")
	private ProductService productService;
	@Resource(name = "commercePriceService")
	private CommercePriceService commercePriceService;
	@Resource(name = "commerceStockService")
	private CommerceStockService commerceStockService;
	@Resource(name = "baseStoreService")
	private BaseStoreService baseStoreService;
	@Resource(name = "modelService")
	private ModelService modelService;
	@Resource(name = "optimizerCalculationService")
	private OptimizerCalculationService calc;
	@Resource(name = "optimizerOpenAiClient")
	private OpenAiClient openAiClient;
	@Resource(name = "optimizerAiResponseValidator")
	private AiResponseValidator aiResponseValidator;

	private final ObjectMapper mapper = new ObjectMapper();

	// ------------------------------------------------------------------------------------------------ public API

	@Override
	public OptimizerResultData generateSuggestedCart(final B2BCustomerModel customer, final B2BUnitModel unit,
			final BudgetTier requestedTier)
	{
		final OptimizerResultData result = new OptimizerResultData();
		result.setAppliedTier(BudgetTier.USUAL);
		result.setProfileSource("EXPLORATORY");
		result.setReasonCode("OK");
		result.setLines(new ArrayList<>());
		final Date now = new Date();
		final Date from = Date.from(ZonedDateTime.now().minusMonths(intProp("history.months", 12)).toInstant());
		final Date openFrom = Date.from(ZonedDateTime.now().minusDays(intProp("open.lookback.days", 120)).toInstant());

		// 1. History with hygiene
		final List<OrderEntryModel> completed = orderHistoryDao.findOrderEntries(unit, from, csv("status.completed"));
		final List<OrderEntryModel> openEntries = orderHistoryDao.findOrderEntries(unit, openFrom, csv("status.open"));

		final Map<String, List<Purchase>> byProduct = new LinkedHashMap<>();
		final Map<String, Double> orderValues = new LinkedHashMap<>();
		collectHistory(completed, byProduct, orderValues);
		final Set<String> openProductCodes = openEntries.stream()
				.filter(e -> e.getProduct() != null && e.getQuantity() != null && e.getQuantity() > 0)
				.map(e -> e.getProduct().getCode()).collect(Collectors.toSet());

		final double totalSpend = orderValues.values().stream().mapToDouble(Double::doubleValue).sum();

		// 2. Candidates
		final List<SuggestionCandidateData> candidates = new ArrayList<>();
		for (final Map.Entry<String, List<Purchase>> e : byProduct.entrySet())
		{
			final SuggestionCandidateData c = buildPatternCandidate(e.getKey(), e.getValue(), now, totalSpend,
					openProductCodes);
			if (c != null)
			{
				candidates.add(c);
			}
		}
		if (configurationService.getConfiguration().getBoolean(P + "affinity.enabled", true))
		{
			candidates.addAll(buildAffinityCandidates(byProduct, candidates, openProductCodes));
		}

		// 3. Rank and number them
		candidates.sort(Comparator.comparingDouble(SuggestionCandidateData::getRelevance).reversed()
				.thenComparing(SuggestionCandidateData::getProductCode));

		// 4. Automatic order-size profile
		applyOrderSizeProfile(result, orderValues.values().stream().mapToDouble(Double::doubleValue).toArray(), requestedTier);

		// 5. Optimizer
		final int maxLines = result.getOrderSizeValue() == null ? intProp("max.lines.exploratory", 10) : intProp("max.lines", 25);
		final List<SuggestionCandidateData> shortlist = new ArrayList<>(candidates.subList(0, Math.min(candidates.size(), maxLines)));
		for (int i = 0; i < shortlist.size(); i++)
		{
			shortlist.get(i).setCandidateId(String.format(Locale.ROOT, "cand_%02d", i + 1));
		}
		final int candidateCount = candidates.size();
		final List<SuggestionCandidateData> selected = optimize(shortlist, result);
		result.setLines(selected);

		// 6. Explanations
		applyTemplates(selected);
		applyAiExplanations(selected, unit, result);

		// 7. Audit (never blocks the response)
		writeAudit(customer, unit, result, candidateCount);
		return result;
	}

	// ------------------------------------------------------------------------------------------------ history

	private void collectHistory(final List<OrderEntryModel> completed,
			final Map<String, List<Purchase>> byProduct, final Map<String, Double> orderValues)
	{
		final Map<String, Purchase> perOrderProduct = new LinkedHashMap<>();
		for (final OrderEntryModel entry : completed)
		{
			if (entry.getProduct() == null || entry.getOrder() == null || entry.getOrder().getDate() == null
					|| entry.getQuantity() == null || entry.getQuantity() <= 0 || Boolean.TRUE.equals(entry.getGiveAway()))
			{
				continue; // free-of-charge scheme goods are not dealer-initiated purchases
			}
			final double net = entry.getQuantity();
			final double value = entry.getTotalPrice() == null ? 0d : entry.getTotalPrice();
			final String orderCode = entry.getOrder().getCode();
			orderValues.merge(orderCode, value, Double::sum);

			final String key = orderCode + "|" + entry.getProduct().getCode();
			final Purchase p = perOrderProduct.computeIfAbsent(key,
					k -> new Purchase(entry.getOrder().getDate(), isSchemePurchase(entry)));
			p.qty += net;
			p.value += value;
		}
		for (final Map.Entry<String, Purchase> e : perOrderProduct.entrySet())
		{
			final String productCode = e.getKey().substring(e.getKey().indexOf('|') + 1);
			byProduct.computeIfAbsent(productCode, k -> new ArrayList<>()).add(e.getValue());
		}
		byProduct.values().forEach(list -> list.sort(Comparator.comparing(p -> p.date)));
	}

	/**
	 * Hook: true if the entry was bought under a scheme/promotion. Such entries are down-weighted
	 * (purchaseoptimizer.scheme.weight, default 0.5) in quantity statistics.
	 * Default is false because the scheme marker is project specific - override with your own attribute.
	 */
	protected boolean isSchemePurchase(final OrderEntryModel entry)
	{
		return false;
	}

	// ------------------------------------------------------------------------------------------------ candidates

	private SuggestionCandidateData buildPatternCandidate(final String code, final List<Purchase> purchases, final Date now,
			final double totalSpend, final Set<String> openProductCodes)
	{
		final int n = purchases.size();
		if (n < intProp("min.orders", 3) || openProductCodes.contains(code))
		{
			return null; // sparse history, or an open PO already covers this product
		}
		final double[] intervals = intervals(purchases);
		if (intervals.length == 0)
		{
			return null;
		}
		final double medianInterval = calc.median(intervals);
		final double cv = calc.coefficientOfVariation(intervals);
		final Date last = purchases.get(n - 1).date;
		final double daysSince = (now.getTime() - last.getTime()) / MS_PER_DAY;
		final double ratio = calc.dueRatio(daysSince, medianInterval);
		final double dueMin = dbl("due.min", 0.85d);
		final double dueMax = dbl("due.max", 1.30d);
		final double due = calc.dueScore(ratio, dueMin, dueMax, dbl("due.drop.above", 5.0d));
		if (due <= 0d)
		{
			return null;
		}

		final EligibleProduct ep = checkEligibility(code);
		if (ep == null)
		{
			return null;
		}

		// quantity
		final double[] qtys = calc.winsoriseUpper(purchases.stream().mapToDouble(p -> p.qty * p.weightFor(dbl("scheme.weight", 0.5d))).toArray());
		final double base = calc.baseQuantity(calc.median(qtys), calc.ewma(qtys, 3d),
				dbl("qty.median.weight", 0.6d), dbl("qty.ewma.weight", 0.4d));
		final double trend = calc.trendFactor(calc.theilSenSlope(qtys), n, base,
				dbl("qty.trend.min", 0.85d), dbl("qty.trend.max", 1.15d));
		final double cap = dbl("qty.p90.cap.factor", 1.25d) * calc.percentile(qtys, 0.9d);
		final long qty = calc.roundQuantity(base * trend, ep.step, ep.minQty, cap, ep.maxQty);
		if (qty <= 0L)
		{
			return null;
		}

		// scoring
		final double spend = purchases.stream().mapToDouble(p -> p.value).sum();
		final double mixScore = totalSpend <= 0d ? 0d : Math.min(1d, spend / totalSpend * 10d);
		final double recency = calc.recencyScore(daysSince);
		final double relevance = calc.relevance(due, mixScore, 0d, recency, dbl("score.due", 0.45d),
				dbl("score.mix", 0.20d), dbl("score.affinity", 0.20d), dbl("score.recency", 0.15d));

		final SuggestionCandidateData c = newCandidate();
		c.setProductCode(code);
		c.setGroup(CandidateGroup.PATTERN);
		c.setOrderCount12m(n);
		c.setDaysSinceLastOrder(Math.round(daysSince));
		c.setMedianIntervalDays(Math.max(1L, Math.round(medianInterval)));
		c.setDueState(ratio <= dueMax ? "APPROACHING_TYPICAL_PERIOD" : "BEYOND_TYPICAL_PERIOD");
		c.setSuggestedQuantity(qty);
		c.setPackSize(ep.step);
		c.setMinQuantity(Math.max(ep.minQty, ep.step));
		c.setUnitPrice(ep.unitPrice);
		c.setRelevance(relevance);
		c.setConfidenceTier(calc.confidenceTier(n, cv, daysSince, medianInterval));
		c.setEvidenceScore(calc.evidenceScore(n, recency, cv, 1.0d));
		c.getEvidenceLabels().add("Observed: " + n + " orders in the last 12 months; last ordered " + c.getDaysSinceLastOrder()
				+ " days ago");
		c.getEvidenceLabels().add("Inferred: typical reorder period about " + c.getMedianIntervalDays() + " days");
		return c;
	}

	private List<SuggestionCandidateData> buildAffinityCandidates(final Map<String, List<Purchase>> byProduct,
			final List<SuggestionCandidateData> existing, final Set<String> openProductCodes)
	{
		final int minSourceOrders = intProp("affinity.min.source.orders", 3);
		final int maxLines = intProp("affinity.max.lines", 3);
		final Set<String> excluded = new HashSet<>(byProduct.keySet());
		existing.forEach(c -> excluded.add(c.getProductCode()));
		excluded.addAll(openProductCodes);

		final Map<String, Double> targets = new TreeMap<>(); // deterministic order
		final Map<String, EligibleProduct> eligible = new HashMap<>();
		for (final Map.Entry<String, List<Purchase>> e : byProduct.entrySet())
		{
			if (e.getValue().size() < minSourceOrders)
			{
				continue;
			}
			final ProductModel source = resolve(e.getKey());
			if (source == null || source.getProductReferences() == null)
			{
				continue;
			}
			for (final ProductReferenceModel ref : source.getProductReferences())
			{
				if (ref.getTarget() == null || Boolean.FALSE.equals(ref.getActive()))
				{
					continue;
				}
				final String tcode = ref.getTarget().getCode();
				if (!excluded.contains(tcode))
				{
					targets.merge(tcode, 1d, Double::sum);
				}
			}
		}
		final List<SuggestionCandidateData> out = new ArrayList<>();
		for (final String tcode : targets.keySet())
		{
			if (out.size() >= maxLines)
			{
				break;
			}
			final EligibleProduct ep = checkEligibility(tcode);
			if (ep == null)
			{
				continue;
			}
			final SuggestionCandidateData c = newCandidate();
			c.setProductCode(tcode);
			c.setGroup(CandidateGroup.AFFINITY);
			c.setConfidenceTier(ConfidenceTier.EXPLORATORY);
			c.setDueState("CATALOGUE_LINK");
			final long q = Math.max(ep.minQty, ep.step);
			c.setSuggestedQuantity(q);
			c.setPackSize(ep.step);
			c.setMinQuantity(q);
			c.setUnitPrice(ep.unitPrice);
			c.setRelevance(dbl("score.affinity", 0.20d));
			c.setEvidenceScore(calc.evidenceScore(0, 0d, 1d, 0.6d));
			c.getEvidenceLabels().add("Inferred: linked in the catalogue to a product you order regularly");
			out.add(c);
		}
		return out;
	}

	private SuggestionCandidateData newCandidate()
	{
		final SuggestionCandidateData c = new SuggestionCandidateData();
		c.setPackSize(1L);
		c.setMinQuantity(1L);
		c.setExplanationSource("TEMPLATE");
		c.setEvidenceLabels(new ArrayList<>());
		return c;
	}

	// ------------------------------------------------------------------------------------------------ eligibility

	private static final class EligibleProduct
	{
		long step = 1;
		long minQty = 1;
		long maxQty = 0;
		double unitPrice;
	}

	/** Anything ineligible is removed BEFORE scoring. Returns null when the product must not be suggested. */
	private EligibleProduct checkEligibility(final String code)
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
		final EligibleProduct ep = new EligibleProduct();
		try
		{
			final PriceInformation pi = commercePriceService.getWebPriceForProduct(p);
			if (pi == null || pi.getPriceValue() == null || pi.getPriceValue().getValue() <= 0d)
			{
				return null;
			}
			ep.unitPrice = pi.getPriceValue().getValue();
		}
		catch (final RuntimeException ex)
		{
			return null; // no price for this dealer's price group
		}
		try
		{
			final BaseStoreModel store = baseStoreService.getCurrentBaseStore();
			if (store != null && commerceStockService.isStockSystemEnabled(store)
					&& StockLevelStatus.OUTOFSTOCK.equals(commerceStockService.getStockLevelStatusForProductAndBaseStore(p, store)))
			{
				return null;
			}
		}
		catch (final RuntimeException ex)
		{
			LOG.debug("Stock check skipped for a product: {}", ex.getClass().getSimpleName());
		}
		ep.step = p.getOrderQuantityInterval() == null || p.getOrderQuantityInterval() < 1 ? 1 : p.getOrderQuantityInterval();
		ep.minQty = p.getMinOrderQuantity() == null || p.getMinOrderQuantity() < 1 ? 1 : p.getMinOrderQuantity();
		ep.maxQty = p.getMaxOrderQuantity() == null || p.getMaxOrderQuantity() < 1 ? 0 : p.getMaxOrderQuantity();
		return ep;
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

	// ------------------------------------------------------------------------------------------------ order-size profile

	private void applyOrderSizeProfile(final OptimizerResultData result, final double[] orderValues, final BudgetTier requested)
	{
		final int minOrders = intProp("budget.min.orders", 3);
		if (orderValues.length < minOrders)
		{
			result.setProfileSource("EXPLORATORY");
			result.setOrderSizeValue(null);
			result.setAppliedTier(BudgetTier.USUAL);
			return;
		}
		final double usual = calc.median(calc.winsoriseUpper(orderValues));
		Double high = null;
		if (orderValues.length >= intProp("budget.high.min.orders", 8))
		{
			final int k = Math.max(4, Math.min(5, intProp("budget.high.k", 4)));
			high = calc.kthHighest(orderValues, k);
		}
		result.setProfileSource("OWN_HISTORY");
		if (BudgetTier.HIGH.equals(requested) && high != null && high >= usual)
		{
			result.setAppliedTier(BudgetTier.HIGH);
			result.setOrderSizeValue(high);
		}
		else
		{
			result.setAppliedTier(BudgetTier.USUAL);
			result.setOrderSizeValue(usual);
		}
	}

	private List<SuggestionCandidateData> optimize(final List<SuggestionCandidateData> shortlist, final OptimizerResultData result)
	{
		final Map<String, Long> packs = calc.selectWithinBudget(shortlist, result.getOrderSizeValue(),
				dbl("budget.granularity", 100d), dbl("diminishing.factor", 0.8d));
		if (shortlist.isEmpty())
		{
			result.setReasonCode("NO_ITEMS");
		}
		else if (result.getOrderSizeValue() == null)
		{
			result.setReasonCode("NO_BUDGET_LIMIT");
		}
		else
		{
			result.setReasonCode(packs.isEmpty() ? "ORDER_SIZE_TOO_LOW" : "OK");
		}
		final List<SuggestionCandidateData> out = new ArrayList<>();
		for (final SuggestionCandidateData c : shortlist) // keep relevance order
		{
			final Long n = packs.get(c.getCandidateId());
			if (n != null && n > 0)
			{
				c.setSuggestedQuantity(n * Math.max(1L, c.getPackSize()));
				out.add(c);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------ explanations

	private void applyTemplates(final List<SuggestionCandidateData> lines)
	{
		for (final SuggestionCandidateData c : lines)
		{
			c.setExplanation(templateFor(c));
			c.setExplanationSource("TEMPLATE");
		}
	}

	static String templateFor(final SuggestionCandidateData c)
	{
		final String lead = "Based on your previous ordering pattern, you may want to review this product for your next purchase. ";
		if (CandidateGroup.AFFINITY.equals(c.getGroup()))
		{
			return lead + "It is linked in the catalogue to products you order regularly. Suggested quantity: "
					+ c.getSuggestedQuantity() + ".";
		}
		final String timing = "BEYOND_TYPICAL_PERIOD".equals(c.getDueState())
				? "It is now beyond its typical reorder period of about " + c.getMedianIntervalDays() + " days (last ordered "
						+ c.getDaysSinceLastOrder() + " days ago)."
				: "It is approaching its typical reorder period of about " + c.getMedianIntervalDays() + " days (last ordered "
						+ c.getDaysSinceLastOrder() + " days ago).";
		return lead + "You ordered it " + c.getOrderCount12m() + " times in the last 12 months. " + timing
				+ " Suggested quantity " + c.getSuggestedQuantity() + " is consistent with your previous orders.";
	}

	private void applyAiExplanations(final List<SuggestionCandidateData> lines, final B2BUnitModel unit, final OptimizerResultData result)
	{
		if (lines.isEmpty() || !configurationService.getConfiguration().getBoolean(P + "ai.enabled", false)
				|| !openAiClient.isConfigured())
		{
			return;
		}
		try
		{
			final List<Map<String, Object>> payload = new ArrayList<>();
			final Map<String, Set<String>> allowedNumbers = new LinkedHashMap<>();
			for (final SuggestionCandidateData c : lines)
			{
				final Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", c.getCandidateId());
				m.put("productCode", c.getProductCode());
				final Map<String, String> attrs = publicAttributes(c.getProductCode());
				m.put("name", attrs.get("name"));
				m.put("category", attrs.get("category"));
				m.put("group", c.getGroup().name());
				m.put("confidenceTier", c.getConfidenceTier().name());
				m.put("dueState", c.getDueState());
				m.put("orderCount12m", c.getOrderCount12m());
				m.put("daysSinceLastOrder", c.getDaysSinceLastOrder());
				m.put("medianIntervalDays", c.getMedianIntervalDays());
				m.put("suggestedQuantity", c.getSuggestedQuantity());
				payload.add(m);
				allowedNumbers.put(c.getCandidateId(), new HashSet<>(Arrays.asList(String.valueOf(c.getOrderCount12m()),
						String.valueOf(c.getDaysSinceLastOrder()), String.valueOf(c.getMedianIntervalDays()),
						String.valueOf(c.getSuggestedQuantity()))));
			}
			final String raw = openAiClient.requestExplanations(String.valueOf(unit.getPk()),
					mapper.writeValueAsString(Collections.singletonMap("candidates", payload)));
			if (raw == null)
			{
				result.setFallbackUsed(true);
				return;
			}
			final Map<String, String> accepted = aiResponseValidator.validate(raw, allowedNumbers,
					intProp("ai.explanation.maxlength", 240));
			if (accepted == null)
			{
				result.setFallbackUsed(true);
				LOG.warn("AI response rejected by validator");
				return;
			}
			for (final SuggestionCandidateData c : lines)
			{
				final String text = accepted.get(c.getCandidateId());
				if (text != null)
				{
					c.setExplanation(text);
					c.setExplanationSource("AI");
					result.setAiUsed(true);
				}
				else
				{
					result.setFallbackUsed(true);
				}
			}
		}
		catch (final Exception e)
		{
			result.setFallbackUsed(true);
			LOG.warn("AI explanation step failed: {}", e.getClass().getSimpleName());
		}
	}

	/** Public catalogue attributes only; control chars stripped and length-limited (names are untrusted text). */
	private Map<String, String> publicAttributes(final String code)
	{
		final Map<String, String> attrs = new HashMap<>();
		attrs.put("name", "");
		attrs.put("category", "");
		final ProductModel p = resolve(code);
		if (p != null)
		{
			attrs.put("name", clean(p.getName()));
			if (p.getSupercategories() != null)
			{
				for (final CategoryModel cat : p.getSupercategories())
				{
					attrs.put("category", clean(cat.getName()));
					break;
				}
			}
		}
		return attrs;
	}

	private static String clean(final String s)
	{
		if (s == null)
		{
			return "";
		}
		final String t = s.replaceAll("[\\p{Cntrl}\\{\\}<>]", " ").trim();
		return t.length() > 80 ? t.substring(0, 80) : t;
	}

	// ------------------------------------------------------------------------------------------------ audit

	private void writeAudit(final B2BCustomerModel customer, final B2BUnitModel unit, final OptimizerResultData result,
			final int candidateCount)
	{
		try
		{
			final OptimizerSuggestionAuditModel audit = modelService.create(OptimizerSuggestionAuditModel.class);
			final String auditId = UUID.randomUUID().toString();
			audit.setAuditId(auditId);
			audit.setB2bUnit(unit);
			audit.setCustomer(customer);
			audit.setBudgetTier(result.getAppliedTier().name());
			audit.setProfileSource(result.getProfileSource());
			audit.setBudgetValue(result.getOrderSizeValue());
			audit.setCandidateCount(candidateCount);
			audit.setSelectedCount(result.getLines().size());
			audit.setAiUsed(result.isAiUsed());
			audit.setFallbackUsed(result.isFallbackUsed());
			audit.setReasonCode(result.getReasonCode());
			final List<Map<String, Object>> sel = new ArrayList<>();
			for (final SuggestionCandidateData c : result.getLines())
			{
				final Map<String, Object> m = new LinkedHashMap<>();
				m.put("candidateId", c.getCandidateId());
				m.put("productCode", c.getProductCode());
				m.put("quantity", c.getSuggestedQuantity());
				m.put("group", c.getGroup().name());
				m.put("tier", c.getConfidenceTier().name());
				sel.add(m);
			}
			audit.setSelectionJson(mapper.writeValueAsString(sel));
			modelService.save(audit);
			result.setAuditId(auditId);
		}
		catch (final Exception e)
		{
			LOG.warn("Could not write optimizer audit: {}", e.getClass().getSimpleName());
		}
	}

	// ------------------------------------------------------------------------------------------------ helpers

	private double[] intervals(final List<Purchase> purchases)
	{
		final List<Double> out = new ArrayList<>();
		for (int i = 1; i < purchases.size(); i++)
		{
			final double days = (purchases.get(i).date.getTime() - purchases.get(i - 1).date.getTime()) / MS_PER_DAY;
			if (days >= 1d)
			{
				out.add(days);
			}
		}
		return out.stream().mapToDouble(Double::doubleValue).toArray();
	}

	private List<String> csv(final String key)
	{
		final String v = configurationService.getConfiguration().getString(P + key, "");
		return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
	}

	private int intProp(final String key, final int def)
	{
		return configurationService.getConfiguration().getInt(P + key, def);
	}

	private double dbl(final String key, final double def)
	{
		return configurationService.getConfiguration().getDouble(P + key, def);
	}

	/** One product purchased on one order . */
	private static final class Purchase
	{
		final Date date;
		final boolean scheme;
		double qty;
		double value;

		Purchase(final Date date, final boolean scheme)
		{
			this.date = date;
			this.scheme = scheme;
		}

		double weightFor(final double configuredSchemeWeight)
		{
			return scheme ? configuredSchemeWeight : 1d;
		}
	}
}
