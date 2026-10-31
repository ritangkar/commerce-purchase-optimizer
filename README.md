# Birla Opus – Suggested Cart (SAP Commerce, OCC)

Copy the folder `birlaopusoptimizer/` into your `custom/` code base, add
`<extension name="birlaopusoptimizer"/>` to `localextensions.xml`, run `ant clean all`, then `ant updatesystem`
(creates the `OptimizerSuggestionAudit` table from `items.xml`).

## The one API

`GET /{baseSiteId}/users/{userId}/optimizer/suggested-cart?budgetTier=USUAL|HIGH&fields=...`
Returns `ProductListWsDTO` (`products[]` = `ProductWsDTO`) extended with: `suggestedQuantity`, `suggestionReason`,
`confidenceTier`, `suggestionGroup` (PATTERN/AFFINITY), `explanationSource` (AI/TEMPLATE), `evidenceLabels`
(Observed/Inferred). No amount is accepted from the client; the order size is derived from the dealer's history.
It never creates a cart or an order.

## Layers (all wired with `@Resource`, beans in `birlaopusoptimizer-spring.xml`)

| Layer | Interface | Impl |
|---|---|---|
| Controller | – | `web/src/.../controller/OptimizerController` |
| Facade | `facade/OptimizerFacade` | `facade/impl/DefaultOptimizerFacade` |
| Service | `service/OptimizerService` | `service/impl/DefaultOptimizerService` |
| DAO | `dao/OptimizerOrderHistoryDao` | `dao/impl/DefaultOptimizerOrderHistoryDao` |

Beans/DTOs: `resources/birlaopusoptimizer-facade-beans.xml` (extends `ProductData`) and
`resources/birlaopusoptimizer-occ-beans.xml` (extends `ProductWsDTO`). New table: `OptimizerSuggestionAudit` in
`birlaopusoptimizer-items.xml` (typecode 31150 – change if it collides).

## What the service does (primary-sales data only)
1. Completed orders of the customer's B2B unit (last 12 months); free-of-charge entries excluded; returned quantities netted; open POs (ordered, not completed) suppress their products.
2. Per product (>= 3 orders): median interval, interval CV, winsorised quantities (Q3+1.5·IQR), EWMA, Theil–Sen trend. Surfaced when `dueRatio` is 0.85–1.30 ("approaching typical reorder period") or above 1.30 ("beyond typical period").
3. Quantity = 0.6·median + 0.4·EWMA, trend clamp 0.85–1.15, rounded to the product's order-quantity interval / MOQ / max, capped at 1.25·P90.
4. Up to 3 catalogue-linked (`ProductReference`) products, tagged EXPLORATORY.
5. Eligibility before scoring: approved, online dates, price for the dealer's price group (`CommercePriceService`), stock status via `CommerceStockService`.
6. Order size derived automatically: USUAL = winsorised median order value (>= 3 orders, else no cap and max 10 lines, profile EXPLORATORY); HIGH = k-th highest (k = 4 or 5) with >= 8 orders, only if the client asks for it.
7. Knapsack (`BasketOptimizer`) picks lines/quantities within that order size using SAP prices.
8. Explanations: OpenAI if enabled, otherwise templates. Output is validated (schema, ids, banned phrases, numbers, length, HTML escape); any failure falls back to templates.
9. Every call is audited (product codes, quantities, flags – no prices/prompts).

## OpenAI
`service/ai/OpenAiClient.java` → `private String open_ai_api_key = "";` Fill it in (or set
`birlaopus.optimizer.ai.openai.apikey`) and set `birlaopus.optimizer.ai.enabled=true`. The model only receives candidate ids,
product code/name/category and counts – no dealer identity, prices or contacts. Without a key the feature works with templates.

## Controller placement (please read)
The controller lives in this extension's `web/` module so it compiles, but OCC security (OAuth2 filters) comes from your
OCC web extension. **Recommended:** move `OptimizerController.java` into your existing OCC extension's `web/src` and add
`<context:component-scan base-package="com.birlaopus.optimizer.controller"/>` to its `spring-mvc` config, then remove the
`<webmodule>` from `extensioninfo.xml`. The facade independently rejects anonymous users, non-B2B customers, and any
`userId` that is not the session user (`current` or own uid).

## Not verified / assumptions (no SAP Commerce install was available here)
* Only the pure logic (`util/*`, `AiResponseValidator`) was compiled and unit-tested (23 tests pass with a stubbed `@UnitTest`). Everything touching platform APIs is written against known OOTB APIs but **has not been compiled** – run `ant clean all` and fix any version differences.
* beans.xml file names: if your version only generates from `<ext>-beans.xml`, merge the two bean files into `birlaopusoptimizer-beans.xml`.
* Scheme/promotion down-weighting is a hook (`isSchemePurchase`, default false) – plug in your project's scheme marker. Seasonality needs >= 24 months of data and is not applied (factor 1.0).
* Child B2B units are not included; credit-limit ceiling is not applied (not inspected). Order status sets are configurable in `project.properties`.
* Product attributes `orderQuantityInterval`, `minOrderQuantity`, `maxOrderQuantity` are used as pack multiple / MOQ / max.
* `essentialdata-optimizer.impex` is optional and must be imported manually.
* Not built (out of scope of the single-API request): nightly snapshot jobs, segments, campaigns, Backoffice config, analytics, AI tool gateway. Config reference: `project.properties` (also `optimizer.properties.sample`).
