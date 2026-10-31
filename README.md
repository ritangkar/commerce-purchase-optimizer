# B2B Suggested Cart (SAP Commerce, OCC)

Copy **both** folders into your `custom/` code base: `purchaseoptimizer/` (core: items, beans, DAO, service, facade) and
`purchaseoptimizerocc/` (OCC controller + WsDTO beans). Add both to `localextensions.xml`, run `ant clean all`, then
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

## Not verified / assumptions (no SAP Commerce install was available here)
* Only the pure logic (`DefaultOptimizerCalculationService`, `AiResponseValidator`) was compiled and unit-tested (12 tests pass, run with a stubbed `@UnitTest` and hand-stubbed generated beans). Everything touching platform APIs is written against known OOTB APIs but **has not been compiled** – run `ant clean all` and fix any version differences.
* beans.xml file names: if your version only generates from `<ext>-beans.xml`, merge the two bean files into `purchaseoptimizer-beans.xml`.
* Scheme/promotion down-weighting is a hook (`isSchemePurchase`, default false) – plug in your project's scheme marker. Seasonality needs >= 24 months of data and is not applied (factor 1.0).
* Child B2B units are not included; credit-limit ceiling is not applied (not inspected). Order status sets are configurable in `project.properties`.
* Product attributes `orderQuantityInterval`, `minOrderQuantity`, `maxOrderQuantity` are used as pack multiple / MOQ / max.
* `essentialdata-optimizer.impex` is optional and must be imported manually.
* Not built (out of scope of the single-API request): nightly snapshot jobs, segments, campaigns, Backoffice config, analytics, AI tool gateway. Config reference: `project.properties` (also `optimizer.properties.sample`).
