package com.purchaseoptimizer.service.impl;

import static org.junit.Assert.assertFalse;

import com.purchaseoptimizer.facade.impl.DefaultOptimizerFacade;

import de.hybris.bootstrap.annotations.UnitTest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;


/**
 * The API only suggests: it must never be able to create a cart or place an order. There is no cart code in this
 * extension, so a cart-integration test would test nothing; this guard fails if someone wires cart/checkout/order
 * services into the suggestion path.
 */
@UnitTest
public class NoCartOrOrderSideEffectsTest
{
	private static final List<Class<?>> CLASSES = Arrays.asList(DefaultOptimizerFacade.class, DefaultOptimizerService.class,
			DefaultOptimizerStrategyService.class, DefaultOptimizerEligibilityService.class,
			DefaultOptimizerCalculationService.class);

	private boolean forbidden(final String name)
	{
		final String n = name.toLowerCase();
		return n.contains("cart") || n.contains("checkout") || n.contains("placeorder") || n.contains("orderservice")
				|| n.contains("orderfacade");
	}

	@Test
	public void noCartCheckoutOrOrderServiceIsInjectedAnywhereInTheSuggestionPath()
	{
		for (final Class<?> c : CLASSES)
		{
			for (final Field f : c.getDeclaredFields())
			{
				assertFalse(c.getSimpleName() + "." + f.getName(), forbidden(f.getType().getSimpleName()));
			}
		}
	}

	@Test
	public void noMethodNamesSuggestWritingCartsOrOrders()
	{
		for (final Class<?> c : CLASSES)
		{
			for (final Method m : c.getDeclaredMethods())
			{
				final String n = m.getName().toLowerCase();
				assertFalse(c.getSimpleName() + "." + m.getName(), n.contains("placeorder") || n.contains("addtocart")
						|| n.contains("createcart") || n.contains("checkout"));
			}
		}
	}
}
