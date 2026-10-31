package com.purchaseoptimizer.service.impl;

import com.purchaseoptimizer.dao.OptimizerStrategyDao;
import com.purchaseoptimizer.data.DealerProfileData;
import com.purchaseoptimizer.data.EligibilityData;
import com.purchaseoptimizer.data.OptimizerConfigData;
import com.purchaseoptimizer.data.OptimizerResultData;
import com.purchaseoptimizer.data.StrategyTraceData;
import com.purchaseoptimizer.data.SuggestionCandidateData;
import com.purchaseoptimizer.enums.CandidateGroup;
import com.purchaseoptimizer.enums.ConfidenceTier;
import com.purchaseoptimizer.enums.RecoStatus;
import com.purchaseoptimizer.model.DealerSegmentModel;
import com.purchaseoptimizer.model.RecoBundleEntryModel;
import com.purchaseoptimizer.model.RecoBundleModel;
import com.purchaseoptimizer.model.RecoCampaignModel;
import com.purchaseoptimizer.model.StrategicPriorityModel;
import com.purchaseoptimizer.service.OptimizerCalculationService;
import com.purchaseoptimizer.service.OptimizerEligibilityService;
import com.purchaseoptimizer.service.OptimizerStrategyService;

import de.hybris.platform.b2b.model.B2BUnitModel;
import de.hybris.platform.category.CategoryService;
import de.hybris.platform.category.model.CategoryModel;
import de.hybris.platform.core.model.c2l.RegionModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.core.model.security.PrincipalGroupModel;
import de.hybris.platform.core.model.user.AddressModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.model.ModelService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Resource;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class DefaultOptimizerStrategyService implements OptimizerStrategyService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultOptimizerStrategyService.class);
	private static final String P = "purchaseoptimizer.";
	private static final Set<RecoStatus> USABLE_ITEM = new HashSet<>(Arrays.asList(RecoStatus.APPROVED, RecoStatus.ACTIVE));

	@Resource(name = "optimizerStrategyDao")
	private OptimizerStrategyDao strategyDao;
	@Resource(name = "optimizerCalculationService")
	private OptimizerCalculationService calc;
	@Resource(name = "optimizerEligibilityService")
	private OptimizerEligibilityService eligibilityService;
	@Resource(name = "categoryService")
	private CategoryService categoryService;
	@Resource(name = "modelService")
	private ModelService modelService;
	@Resource(name = "configurationService")
	private ConfigurationService configurationService;

	private volatile boolean secretWarned;

	// ------------------------------------------------------------------------------------------------ segmentation

	@Override
	public DealerProfileData resolveDealerProfile(final B2BUnitModel unit, final int orderCount12m, final Double usualOrderValue)
	{
		final DealerProfileData p = new DealerProfileData();
		p.setUnitUid(unit.getUid());
		p.setOrderCount12m(orderCount12m);
		p.setUsualOrderValue(usualOrderValue);
		p.setRegion(regionOf(unit));
		final Set<String> groups = new HashSet<>();
		if (unit.getGroups() != null)
		{
			for (final PrincipalGroupModel g : unit.getGroups())
			{
				groups.add(g.getUid().toLowerCase(Locale.ROOT));
			}
		}
		final List<String> codes = new ArrayList<>();
		for (final DealerSegmentModel s : strategyDao.findAllSegments())
		{
			if (matches(s, p, groups))
			{
				codes.add(s.getCode());
			}
		}
		p.setSegmentCodes(codes);
		return p;
	}

	private String regionOf(final B2BUnitModel unit)
	{
		if (unit.getAddresses() != null)
		{
			for (final AddressModel a : unit.getAddresses())
			{
				final RegionModel r = a.getRegion();
				if (r != null && r.getIsocode() != null)
				{
					return r.getIsocode();
				}
			}
		}
		return "";
	}

	private boolean matches(final DealerSegmentModel s, final DealerProfileData p, final Set<String> groups)
	{
		if (StringUtils.isNotBlank(s.getRegion()) && !s.getRegion().equalsIgnoreCase(p.getRegion()))
		{
			return false;
		}
		if (StringUtils.isNotBlank(s.getDealerType()) && !groups.contains(s.getDealerType().toLowerCase(Locale.ROOT)))
		{
			return false;
		}
		if (s.getMinOrdersPerYear() != null && p.getOrderCount12m() < s.getMinOrdersPerYear())
		{
			return false;
		}
		if (s.getMaxOrdersPerYear() != null && p.getOrderCount12m() > s.getMaxOrdersPerYear())
		{
			return false;
		}
		if (s.getMinOrderValue() != null || s.getMaxOrderValue() != null)
		{
			if (p.getUsualOrderValue() == null)
			{
				return false; // value rule cannot be evaluated without history
			}
			if (s.getMinOrderValue() != null && p.getUsualOrderValue() < s.getMinOrderValue())
			{
				return false;
			}
			if (s.getMaxOrderValue() != null && p.getUsualOrderValue() > s.getMaxOrderValue())
			{
				return false;
			}
		}
		return true;
	}

	private boolean targets(final Collection<DealerSegmentModel> segments, final Collection<String> regions,
			final DealerProfileData profile)
	{
		if (segments != null && !segments.isEmpty())
		{
			boolean hit = false;
			for (final DealerSegmentModel s : segments)
			{
				if (profile.getSegmentCodes().contains(s.getCode()))
				{
					hit = true;
					break;
				}
			}
			if (!hit)
			{
				return false;
			}
		}
		if (regions != null && !regions.isEmpty())
		{
			return regions.stream().anyMatch(r -> r != null && r.equalsIgnoreCase(profile.getRegion()));
		}
		return true;
	}

	// ------------------------------------------------------------------------------------------------ blend

	@Override
	public List<SuggestionCandidateData> blend(final DealerProfileData profile, final OptimizerConfigData cfg,
			final List<SuggestionCandidateData> existing, final Map<String, Integer> orderCountByProduct,
			final Map<String, Double> medianQtyByProduct, final Set<String> excluded, final OptimizerResultData result)
	{
		final List<StrategyTraceData> trace = new ArrayList<>();
		result.setStrategyTrace(trace);
		result.setHoldoutKeyId(configurationService.getConfiguration().getString(P + "holdout.hmac.key.id", "k1"));
		final List<SuggestionCandidateData> out = new ArrayList<>(existing);
		if (!configurationService.getConfiguration().getBoolean(P + "strategy.enabled", true))
		{
			return out;
		}
		final Date now = new Date();

		// the dealer's own category activity (observed): purchases and typical quantities per category
		final Map<String, Integer> catOrders = new HashMap<>();
		final Map<String, List<Double>> catQtys = new HashMap<>();
		final List<Double> allQtys = new ArrayList<>();
		for (final Map.Entry<String, Integer> e : orderCountByProduct.entrySet())
		{
			final double q = medianQtyByProduct.getOrDefault(e.getKey(), 0d);
			allQtys.add(q);
			for (final String cat : eligibilityService.categoryCodes(e.getKey()))
			{
				catOrders.merge(cat, e.getValue(), Integer::sum);
				catQtys.computeIfAbsent(cat, k -> new ArrayList<>()).add(q);
			}
		}

		final Map<String, SuggestionCandidateData> byCode = new LinkedHashMap<>();
		out.forEach(c -> byCode.put(c.getProductCode(), c));
		final double wMix = configurationService.getConfiguration().getDouble("purchaseoptimizer.score.mix", 0.20d);
		final double bundleBase = configurationService.getConfiguration().getDouble(P + "bundle.base.relevance", 0.30d);

		for (final RecoCampaignModel campaign : sortedCampaigns(strategyDao.findCampaignsByStatus(Arrays.asList(RecoStatus.ACTIVE))))
		{
			if (!inWindow(campaign.getValidFrom(), campaign.getValidTo(), now)
					|| !targets(campaign.getSegments(), campaign.getRegions(), profile))
			{
				continue;
			}
			final boolean holdout = calc.isHoldout(holdoutSecret(), campaign.getCode(), profile.getUnitUid(),
					campaign.getHoldoutPercent() == null ? 0 : campaign.getHoldoutPercent());
			if (holdout)
			{
				trace(trace, campaign.getCode(), null, null, 0d, true, "HOLDOUT_NO_BOOST");
				continue;
			}

			// ---- priorities
			for (final StrategicPriorityModel pr : sortedBy(campaign.getPriorities(), StrategicPriorityModel::getCode))
			{
				if (!USABLE_ITEM.contains(pr.getStatus()) || !inWindow(pr.getValidFrom(), pr.getValidTo(), now)
						|| !targets(pr.getSegments(), pr.getRegions(), profile))
				{
					trace(trace, campaign.getCode(), pr.getCode(), null, 0d, false, "PRIORITY_NOT_USABLE");
					continue;
				}
				final double boost = pr.getBoost() == null ? 0d : pr.getBoost();
				for (final String code : targetProducts(pr))
				{
					if (excluded.contains(code))
					{
						continue;
					}
					final EligibilityData el = eligibilityService.check(code); // eligibility BEFORE scoring
					if (el == null)
					{
						continue;
					}
					final int related = relatedOrders(el.getCategoryCodes(), catOrders);
					final boolean gate = calc.gateOpen(related, cfg.getGateMinCategoryOrders());
					final SuggestionCandidateData current = byCode.get(code);
					if (!gate)
					{
						trace(trace, campaign.getCode(), pr.getCode(), null, 0d, false, "GATE_CLOSED:" + code);
						continue;
					}
					if (current != null)
					{
						final double newBoost = Math.min(Math.max(boost, current.getBoostApplied()), cfg.getBoostCap());
						if (newBoost > current.getBoostApplied())
						{
							current.setRelevance(current.getRelevance() + newBoost - current.getBoostApplied());
							current.setBoostApplied(newBoost);
							current.setStrategicCode(pr.getCode());
							current.getEvidenceLabels().add("Ranking: a current business priority applies to this product");
							trace(trace, campaign.getCode(), pr.getCode(), null, newBoost, false, "BOOSTED:" + code);
						}
					}
					else
					{
						final double typical = typicalQty(el.getCategoryCodes(), catQtys, allQtys);
						final long qty = calc.scaleBundleQuantity(typical, 1d, el.getStep(), el.getMinQty(), el.getMaxQty());
						if (qty <= 0L)
						{
							continue;
						}
						final double base = wMix * Math.min(1d, related / 12d);
						final SuggestionCandidateData c = newCandidate(code, CandidateGroup.STRATEGIC, el, qty);
						c.setBoostApplied(Math.min(boost, cfg.getBoostCap()));
						c.setRelevance(calc.finalScore(base, boost, cfg.getBoostCap(), true));
						c.setStrategicCode(pr.getCode());
						c.getEvidenceLabels().add("Observed: " + related + " purchases of related products in the last 12 months");
						c.getEvidenceLabels().add("Business priority: promoted by your supplier, not derived from your ordering pattern");
						out.add(c);
						byCode.put(code, c);
						trace(trace, campaign.getCode(), pr.getCode(), null, c.getBoostApplied(), false, "ADDED:" + code);
					}
				}
			}

			// ---- bundles (all-or-nothing)
			for (final RecoBundleModel bundle : sortedBy(campaign.getBundles(), RecoBundleModel::getCode))
			{
				addBundle(campaign, bundle, profile, cfg, now, bundleBase, catOrders, catQtys, allQtys, excluded, out, byCode, trace);
			}
		}
		return out;
	}

	private void addBundle(final RecoCampaignModel campaign, final RecoBundleModel bundle, final DealerProfileData profile,
			final OptimizerConfigData cfg, final Date now, final double bundleBase, final Map<String, Integer> catOrders,
			final Map<String, List<Double>> catQtys, final List<Double> allQtys, final Set<String> excluded,
			final List<SuggestionCandidateData> out, final Map<String, SuggestionCandidateData> byCode,
			final List<StrategyTraceData> trace)
	{
		if (!USABLE_ITEM.contains(bundle.getStatus()) || !inWindow(bundle.getValidFrom(), bundle.getValidTo(), now)
				|| bundle.getEntries() == null || bundle.getEntries().isEmpty())
		{
			trace(trace, campaign.getCode(), null, bundle.getCode(), 0d, false, "BUNDLE_NOT_USABLE");
			return;
		}
		final List<SuggestionCandidateData> lines = new ArrayList<>();
		int related = 0;
		for (final RecoBundleEntryModel entry : bundle.getEntries())
		{
			final String code = entry.getProduct() == null ? null : entry.getProduct().getCode();
			final EligibilityData el = code == null || excluded.contains(code) ? null : eligibilityService.check(code);
			final double typical = el == null ? 0d : typicalQty(el.getCategoryCodes(), catQtys, allQtys);
			final long qty = el == null || entry.getQtyRatio() == null ? 0L
					: calc.scaleBundleQuantity(typical, entry.getQtyRatio(), el.getStep(), el.getMinQty(), el.getMaxQty());
			if (el == null || qty <= 0L)
			{
				trace(trace, campaign.getCode(), null, bundle.getCode(), 0d, false, "BUNDLE_DROPPED_INELIGIBLE_LINE");
				return; // all-or-nothing: one unusable line removes the whole bundle
			}
			related = Math.max(related, relatedOrders(el.getCategoryCodes(), catOrders));
			final SuggestionCandidateData c = newCandidate(code, CandidateGroup.BUNDLE_CAMPAIGN, el, qty);
			c.setBundleCode(bundle.getCode());
			c.setStrategicCode(campaign.getCode());
			lines.add(c);
		}
		if (!calc.gateOpen(related, cfg.getGateMinCategoryOrders()))
		{
			trace(trace, campaign.getCode(), null, bundle.getCode(), 0d, false, "GATE_CLOSED");
			return;
		}
		final double relevance = calc.finalScore(bundleBase, cfg.getBoostCap(), cfg.getBoostCap(), true);
		for (final SuggestionCandidateData c : lines)
		{
			final SuggestionCandidateData dup = byCode.get(c.getProductCode());
			if (dup != null) // the bundle owns the product; keep the larger of the two quantities
			{
				c.setSuggestedQuantity(Math.max(c.getSuggestedQuantity(), dup.getSuggestedQuantity()));
				out.remove(dup);
			}
			c.setBoostApplied(cfg.getBoostCap());
			c.setRelevance(relevance);
			c.getEvidenceLabels().add("Observed: " + related + " purchases of related products in the last 12 months");
			c.getEvidenceLabels().add("Campaign bundle: all lines are suggested together");
			out.add(c);
			byCode.put(c.getProductCode(), c);
		}
		trace(trace, campaign.getCode(), null, bundle.getCode(), cfg.getBoostCap(), false, "BUNDLE_ADDED");
	}

	private SuggestionCandidateData newCandidate(final String code, final CandidateGroup group, final EligibilityData el,
			final long qty)
	{
		final SuggestionCandidateData c = new SuggestionCandidateData();
		c.setProductCode(code);
		c.setGroup(group);
		c.setConfidenceTier(ConfidenceTier.EXPLORATORY); // never evidence-based on own pattern
		c.setDueState("BUSINESS_PRIORITY");
		c.setSuggestedQuantity(qty);
		c.setPackSize(el.getStep());
		c.setMinQuantity(Math.max(el.getMinQty(), el.getStep()));
		c.setUnitPrice(el.getUnitPrice());
		c.setEvidenceScore(calc.evidenceScore(0, 0d, 1d, 0.3d));
		c.setExplanationSource("TEMPLATE");
		c.setEvidenceLabels(new ArrayList<>());
		return c;
	}

	private double typicalQty(final Collection<String> cats, final Map<String, List<Double>> catQtys, final List<Double> all)
	{
		final List<Double> pool = new ArrayList<>();
		for (final String cat : cats)
		{
			pool.addAll(catQtys.getOrDefault(cat, Collections.emptyList()));
		}
		return calc.median((pool.isEmpty() ? all : pool).stream().mapToDouble(Double::doubleValue).toArray());
	}

	private int relatedOrders(final Collection<String> cats, final Map<String, Integer> catOrders)
	{
		int best = 0;
		for (final String cat : cats)
		{
			best = Math.max(best, catOrders.getOrDefault(cat, 0));
		}
		return best;
	}

	private Set<String> targetProducts(final StrategicPriorityModel pr)
	{
		final Set<String> codes = new TreeSet<>();
		if (pr.getProduct() != null)
		{
			codes.add(pr.getProduct().getCode());
		}
		else if (pr.getCategory() != null)
		{
			final int limit = configurationService.getConfiguration().getInt(P + "strategy.max.category.products", 200);
			final List<CategoryModel> cats = new ArrayList<>();
			cats.add(pr.getCategory());
			cats.addAll(categoryService.getAllSubcategoriesForCategory(pr.getCategory()));
			for (final CategoryModel c : cats)
			{
				if (c.getProducts() != null)
				{
					for (final ProductModel p : c.getProducts())
					{
						if (codes.size() >= limit)
						{
							return codes;
						}
						codes.add(p.getCode());
					}
				}
			}
		}
		return codes;
	}

	private String holdoutSecret()
	{
		final String s = configurationService.getConfiguration().getString(P + "holdout.hmac.secret", "");
		if (StringUtils.isBlank(s))
		{
			if (!secretWarned)
			{
				secretWarned = true;
				LOG.warn("purchaseoptimizer.holdout.hmac.secret is not set; holdout assignment uses a predictable fallback key");
			}
			return "unconfigured-holdout-key";
		}
		return s;
	}

	private void trace(final List<StrategyTraceData> trace, final String campaign, final String priority, final String bundle,
			final double boost, final boolean holdout, final String outcome)
	{
		final StrategyTraceData t = new StrategyTraceData();
		t.setCampaignCode(campaign);
		t.setPriorityCode(priority);
		t.setBundleCode(bundle);
		t.setBoost(boost);
		t.setHoldout(holdout);
		t.setOutcome(outcome);
		trace.add(t);
	}

	private boolean inWindow(final Date from, final Date to, final Date now)
	{
		return from != null && to != null && !now.before(from) && !now.after(to);
	}

	private List<RecoCampaignModel> sortedCampaigns(final List<RecoCampaignModel> in)
	{
		final List<RecoCampaignModel> l = new ArrayList<>(in);
		l.sort(Comparator.comparing(RecoCampaignModel::getCode));
		return l;
	}

	private <T> List<T> sortedBy(final Collection<T> in, final java.util.function.Function<T, String> key)
	{
		final List<T> l = in == null ? new ArrayList<>() : new ArrayList<>(in);
		l.sort(Comparator.comparing(key));
		return l;
	}

	// ------------------------------------------------------------------------------------------------ lifecycle

	@Override
	public int[] runLifecycle(final Date now)
	{
		int activated = 0;
		int expired = 0;
		final Set<Object> seen = new LinkedHashSet<>();
		for (final RecoCampaignModel c : strategyDao.findCampaignsByStatus(Arrays.asList(RecoStatus.APPROVED, RecoStatus.ACTIVE)))
		{
			int[] r = advance(c, c.getStatus(), c.getValidFrom(), c.getValidTo(), now);
			activated += r[0];
			expired += r[1];
			for (final StrategicPriorityModel p : nullSafe(c.getPriorities()))
			{
				if (seen.add(p))
				{
					r = advance(p, p.getStatus(), p.getValidFrom(), p.getValidTo(), now);
					activated += r[0];
					expired += r[1];
				}
			}
			for (final RecoBundleModel b : nullSafe(c.getBundles()))
			{
				if (seen.add(b))
				{
					r = advance(b, b.getStatus(), b.getValidFrom(), b.getValidTo(), now);
					activated += r[0];
					expired += r[1];
				}
			}
		}
		LOG.info("Reco lifecycle: {} activated, {} expired", activated, expired);
		return new int[] { activated, expired };
	}

	private <T> Collection<T> nullSafe(final Collection<T> c)
	{
		return c == null ? Collections.emptyList() : c;
	}

	/** Sets the new status on the model, saves it and returns {activated, expired}. */
	private int[] advance(final Object item, final RecoStatus status, final Date from, final Date to, final Date now)
	{
		if (from == null || to == null || (status != RecoStatus.APPROVED && status != RecoStatus.ACTIVE))
		{
			return new int[] { 0, 0 };
		}
		RecoStatus target = null;
		if (now.after(to))
		{
			target = RecoStatus.EXPIRED;
		}
		else if (status == RecoStatus.APPROVED && !now.before(from))
		{
			target = RecoStatus.ACTIVE;
		}
		if (target == null)
		{
			return new int[] { 0, 0 };
		}
		try
		{
			if (item instanceof RecoCampaignModel)
			{
				((RecoCampaignModel) item).setStatus(target);
			}
			else if (item instanceof StrategicPriorityModel)
			{
				((StrategicPriorityModel) item).setStatus(target);
			}
			else
			{
				((RecoBundleModel) item).setStatus(target);
			}
			modelService.save(item);
			return target == RecoStatus.ACTIVE ? new int[] { 1, 0 } : new int[] { 0, 1 };
		}
		catch (final RuntimeException e)
		{
			LOG.warn("Could not move an item to {}: {}", target, e.getMessage());
			return new int[] { 0, 0 };
		}
	}
}
