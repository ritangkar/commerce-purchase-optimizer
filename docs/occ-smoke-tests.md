# OCC smoke tests (manual – the OAuth filter chain cannot be exercised by unit tests)

These need a running SAP Commerce with the OCC extension deployed. They were **not run** by the author.
`$HOST` = your OCC host, `$SITE` = base site id, tokens come from your OAuth2 authorization server.

| # | Request | Expected |
|---|---|---|
| 1 | `GET /occ/v2/$SITE/users/current/optimizer/suggested-cart` with a B2B customer token | 200, `products[]`, groups `PATTERN`/`AFFINITY`/`STRATEGIC`/`BUNDLE_CAMPAIGN` in `suggestionGroup` |
| 2 | Same without any token | 401 |
| 3 | Same with a client-credentials token that has no customer role and no trusted-client rights | 403 |
| 4 | `.../users/<another dealer's uid>/optimizer/suggested-cart` with dealer A's token | 403 |
| 5 | `.../users/current/optimizer/suggested-cart?budget=5000` (also `amount`, `boost`, `campaign`) | 400 `Unsupported parameter` |
| 6 | `?budgetTier=5000` | 400 `Unsupported budgetTier` |
| 7 | `?budgetTier=HIGH` for a dealer with fewer than 8 completed orders | 200, behaves like `USUAL` |
| 8 | `POST`/`PUT`/`DELETE` on the same path | 405 (no cart or order is ever created) |
| 9 | `purchaseoptimizer.enabled=false` | 404 |
| 10 | Call as a dealer in a campaign holdout, then check `OptimizerSuggestionAudit.appliedStrategyJson` in Backoffice | `"holdout":true`, no `STRATEGIC`/`BUNDLE_CAMPAIGN` line |
| 11 | Response and error bodies | no stack traces, no prices outside `price`, no dealer names |
