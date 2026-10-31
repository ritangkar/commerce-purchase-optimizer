package com.purchaseoptimizer.enums;

/** Order-size tier derived from the dealer's own history. The client never supplies an amount. */
public enum BudgetTier
{
	USUAL, HIGH;

	public static BudgetTier fromString(final String value)
	{
		if (value == null || value.trim().isEmpty())
		{
			return USUAL;
		}
		for (final BudgetTier tier : values())
		{
			if (tier.name().equalsIgnoreCase(value.trim()))
			{
				return tier;
			}
		}
		throw new IllegalArgumentException("Unsupported budgetTier");
	}
}
