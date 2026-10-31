# B2B Suggested Cart (SAP Commerce, OCC)

Copy the folders into your `custom/` code base: `purchaseoptimizer/` (core: items, beans, DAO, services, facade, interceptors, job),
`purchaseoptimizerocc/` (OCC controller + WsDTO beans) and, for business users, `purchaseoptimizerbackoffice/` (cockpitng config). Add them to `localextensions.xml`, run `ant clean all`, then
`ant updatesystem` (creates the `OptimizerSuggestionAudit` table). If you already have a custom OCC extension, move
`OptimizerController.java` (and `purchaseoptimizerocc-beans.xml`) into it and drop `purchaseoptimizerocc/`.

## The one API

`GET /{baseSiteId}/users/{userId}/optimizer/suggested-cart?budgetTier=USUAL|HIGH&fields=...`
Returns `ProductListWsDTO` (`products[]` = `ProductWsDTO`) extended with: `suggestedQuantity`, `suggestionReason`,
`confidenceTier`, `suggestionGroup` (PATTERN/AFFINITY), `explanationSource` (AI/TEMPLATE), `evidenceLabels`
(Observed/Inferred). No amount is accepted from the client; the order size is derived from the dealer's history.
It never creates a cart or an order.

## Layers (all wired with `@Resource`; core beans in `purchaseoptimizer-spring.xml`)

| Layer | Interface | Impl |
|---|---|---|
| Controller (OCC ext.) | – | `purchaseoptimizerocc/web/src/.../occ/controller/OptimizerController` (thin: calls facade, maps to WsDTO) |
| Facade | `facade/OptimizerFacade` | `facade/impl/DefaultOptimizerFacade` (auth/isolation, tier parsing, ProductData assembly) |
| Service | `service/OptimizerService` | `service/impl/DefaultOptimizerService` (pipeline) |
| Service | `service/OptimizerCalculationService` | `service/impl/DefaultOptimizerCalculationService` (statistics, rules, knapsack) |
| DAO | `dao/OptimizerOrderHistoryDao` | `dao/impl/DefaultOptimizerOrderHistoryDao` |

No hand-written DTO/WsDTO classes: everything is generated from beans XML.
* `purchaseoptimizer-facade-beans.xml`: enums `BudgetTier`, `ConfidenceTier`, `CandidateGroup`; data beans `SuggestionCandidateData`, `OptimizerResultData`; extends `ProductData`.
* `purchaseoptimizerocc-beans.xml`: extends `ProductWsDTO` with the same fields.
* New table `OptimizerSuggestionAudit` in `purchaseoptimizer-items.xml` (typecode 31150 – change if it collides).

## What the service does (primary-sales data only)
1. Completed orders of the customer's B2B unit (last 12 months); free-of-charge entries excluded; return orders are not handled (not applicable in B2B); open POs (ordered, not completed) suppress their products.
2. Per product (>= 3 orders): median interval, interval CV, winsorised quantities (Q3+1.5·IQR), EWMA, Theil–Sen trend. Surfaced when `dueRatio` is 0.85–1.30 ("approaching typical reorder period") or above 1.30 ("beyond typical period").
3. Quantity = 0.6·median + 0.4·EWMA, trend clamp 0.85–1.15, rounded to the product's order-quantity interval / MOQ / max, capped at 1.25·P90.
4. Up to 3 catalogue-linked (`ProductReference`) products, tagged EXPLORATORY.
5. Eligibility before scoring: approved, online dates, price for the dealer's price group (`CommercePriceService`), stock status via `CommerceStockService`.
6. Order size derived automatically: USUAL = winsorised median order value (>= 3 orders, else no cap and max 10 lines, profile EXPLORATORY); HIGH = k-th highest (k = 4 or 5) with >= 8 orders, only if the client asks for it.
7. Knapsack (`OptimizerCalculationService.selectWithinBudget`) picks lines/quantities within that order size using SAP prices.
8. Explanations: OpenAI if enabled, otherwise templates. Output is validated (schema, ids, banned phrases, numbers, length, HTML escape); any failure falls back to templates.
9. Every call is audited (product codes, quantities, flags – no prices/prompts).

## OpenAI
`service/ai/OpenAiClient.java` → `private String open_ai_api_key = "";` Fill it in (or set
`purchaseoptimizer.ai.openai.apikey`) and set `purchaseoptimizer.ai.enabled=true`. The model only receives candidate ids,
product code/name/category and counts – no dealer identity, prices or contacts. Without a key the feature works with templates.

## Security
OAuth2 comes from the OCC web extension (`@Secured` on the controller). The facade independently rejects anonymous users,
non-B2B customers and any `userId` that is not the session user (`current` or own uid).

## Business configuration: pushing products (all deterministic, no AI)
Edited in Backoffice (`purchaseoptimizerbackoffice`), stored in `purchaseoptimizer-items.xml`:
`OptimizerConfig` (boost cap, strategic line/value share caps, evidence thresholds, versioned), `DealerSegment` (rule based:
region, dealer-type group, usual order value band, orders per year band), `StrategicPriority` (product **or** category, bounded
boost, dates, segments, regions), `RecoBundle` + `RecoBundleEntry` (qty ratio; optional SAP promotion code, informational only –
pricing always comes from SAP), `RecoCampaign` (dates, segments, regions, priorities, bundles, `holdoutPercent`, `createdBy`, `approvedBy`).

How the engine uses them (`DefaultOptimizerStrategyService`, `DefaultOptimizerCalculationService`):
1. Only **ACTIVE** campaigns inside their dates that target the dealer's segment and region; priorities/bundles must be APPROVED/ACTIVE and in their own dates.
2. Per campaign, deterministic **holdout**: HMAC-SHA256(secret, campaign|dealer) mod 100 < holdoutPercent -> no boost, still written to the audit.
3. Eligibility (approved, online, priced for the dealer, stock status) runs **before** scoring; ineligible products never appear.
4. **Relevance gate**: no boost unless the dealer bought related-category products at least `gateMinCategoryOrders` times in 12 months.
5. `final = relevance + min(boost, boostCap) x gate`. Boosting an existing pattern line keeps it PATTERN; new products become `STRATEGIC`, campaign bundles `BUNDLE_CAMPAIGN` (always EXPLORATORY, template wording, never AI).
6. Bundle quantity = dealer's typical quantity (related categories) x `qtyRatio`, rounded to pack/MOQ; bundles are **all-or-nothing** in the budget fit and in cap enforcement.
7. After budget fit: strategic + bundle lines <= `strategicLineShareCap` of lines (rounded down) and <= `strategicValueShareCap` of cart value; weakest unit removed first.

Governance: status DRAFT -> IN_REVIEW -> APPROVED -> ACTIVE -> EXPIRED enforced by validate interceptors
(`RecoCampaign`, `StrategicPriority`, `RecoBundle`). `createdBy`/`approvedBy` are stamped from the session (cannot be forged);
maker-checker blocks `createdBy == approvedBy`; only members of group `campaignapprover` (or admin) can approve; approved/active
content is frozen (send back to DRAFT to edit); ACTIVE/EXPIRED are set by `campaignLifecycleJob` (hourly). ImpEx:
`essentialdata-optimizer.impex` (roles `campaignmanager`, `campaignapprover`, `optimizerconfigadmin`, `optimizerauditor`, type rights,
default config, job, cronjob, trigger) and `sampledata-optimizer-DEMO-ONLY.impex` (demo segments, never for production).
Set `purchaseoptimizer.holdout.hmac.secret` (env/secrets) and keep `holdout.hmac.key.id` in step when rotating.

Dealer API: groups are returned separately and labelled (`suggestionGroup` = PATTERN / AFFINITY / STRATEGIC / BUNDLE_CAMPAIGN).
The endpoint rejects any query parameter other than `budgetTier`, `fields`, `lang`, `curr`, so no amount, boost or campaign can be sent.
Every call writes `OptimizerSuggestionAudit` incl. `appliedStrategyJson` (campaign, priority, bundle, boost, holdout, outcome) and `holdoutKeyId`.

## Tests
* Run and passing in the authoring environment (21 tests, stubbed `@UnitTest` and generated beans): `DefaultOptimizerCalculationServiceTest`, `AiResponseValidatorTest`.
* Written but **not run** (need the SAP Commerce test classpath/Mockito): `DefaultOptimizerStrategyServiceTest` (gate, eligibility, holdout, targeting, validity, bundles, lifecycle), `RecoCampaignInterceptorsTest` (maker-checker, workflow, approver group, frozen content), `DefaultOptimizerFacadeTest` (dealer isolation, rejected parameters).
* Missing: DAO/ServicelayerTest integration tests, OCC controller/OAuth scope tests, cart-integration tests.

## Not verified / assumptions (no SAP Commerce install was available here)
* Everything that touches platform APIs is **uncompiled** (only a syntax-level `javac` pass was done). Run `ant clean all` and fix version differences first.
* beans.xml file names: the OCC one uses the standard `<ext>-beans.xml` name. If `purchaseoptimizer-facade-beans.xml` is not picked up, rename it `purchaseoptimizer-beans.xml`.
* Backoffice: the extension scaffold (`purchaseoptimizerbackoffice`) and cockpitng XML are unverified against your Backoffice version; if needed generate a module with `ant extgen -Dinput.template=ybackoffice` and copy only the config XML. No explorer-tree node is added (types are reachable via Backoffice search); add the groups to a Backoffice role so members can log in.
* Segments: `region` = isocode of the first dealer-unit address with a region; `dealerType` = uid of a user group the unit belongs to. Adapt `resolveDealerProfile` if your model differs.
* Peer (comparable-dealer) evidence for the relevance gate is **not implemented**; the gate uses the dealer's own related-category history only.
* Strategic category targeting expands to direct and sub-category products, capped by `purchaseoptimizer.strategy.max.category.products`.
* Scheme/promotion down-weighting is a hook (`isSchemePurchase`, default false). Seasonality, child B2B units and a credit-limit ceiling are not applied.
* Order status sets and all tuning fall back to `project.properties` when no active `OptimizerConfig` exists.
* Not built: nightly snapshot jobs, analytics/uplift reporting, AI tool gateway.
