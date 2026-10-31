package com.purchaseoptimizer.facade.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.purchaseoptimizer.exception.OptimizerException;
import com.purchaseoptimizer.service.OptimizerService;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.b2b.model.B2BCustomerModel;
import de.hybris.platform.b2b.model.B2BUnitModel;
import de.hybris.platform.b2b.services.B2BUnitService;
import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.user.UserService;

import java.util.Arrays;
import java.util.Collections;

import org.apache.commons.configuration.PropertiesConfiguration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;


/** Dealer isolation and input rules. NOT executed in the authoring environment (needs the platform test classpath). */
@UnitTest
@RunWith(MockitoJUnitRunner.Silent.class)
public class DefaultOptimizerFacadeTest
{
	@Mock
	private OptimizerService optimizerService;
	@Mock
	private UserService userService;
	@Mock
	private B2BUnitService<B2BUnitModel, B2BCustomerModel> b2bUnitService;
	@Mock
	private ConfigurationService configurationService;
	@InjectMocks
	private DefaultOptimizerFacade facade;

	private final B2BCustomerModel customer = mock(B2BCustomerModel.class);

	@Before
	public void setUp()
	{
		when(configurationService.getConfiguration()).thenReturn(new PropertiesConfiguration());
		when(customer.getUid()).thenReturn("dealer@example.com");
		when(userService.getCurrentUser()).thenReturn(customer);
		when(b2bUnitService.getParent(customer)).thenReturn(mock(B2BUnitModel.class));
	}

	private void expectReason(final OptimizerException.Reason reason, final Runnable call)
	{
		try
		{
			call.run();
			fail("expected " + reason);
		}
		catch (final OptimizerException e)
		{
			assertEquals(reason, e.getReason());
		}
		verify(optimizerService, never()).generateSuggestedCart(any(), any(), any());
	}

	@Test
	public void otherDealerUserIdIsDenied()
	{
		expectReason(OptimizerException.Reason.ACCESS_DENIED,
				() -> facade.getSuggestedCart("someone.else@example.com", "USUAL", Collections.emptyList()));
	}

	@Test
	public void anonymousAndNonB2BUsersAreDenied()
	{
		final UserModel anon = mock(UserModel.class);
		when(userService.getCurrentUser()).thenReturn(anon);
		when(userService.isAnonymousUser(anon)).thenReturn(true);
		expectReason(OptimizerException.Reason.ACCESS_DENIED,
				() -> facade.getSuggestedCart("current", "USUAL", Collections.emptyList()));

		final UserModel plain = mock(UserModel.class); // not a B2BCustomer
		when(userService.getCurrentUser()).thenReturn(plain);
		expectReason(OptimizerException.Reason.ACCESS_DENIED,
				() -> facade.getSuggestedCart("current", "USUAL", Collections.emptyList()));
	}

	@Test
	public void clientCannotSendAmountBoostOrCampaign()
	{
		for (final String p : Arrays.asList("budget", "amount", "boost", "campaign", "budgetAmount"))
		{
			expectReason(OptimizerException.Reason.BAD_REQUEST,
					() -> facade.getSuggestedCart("current", "USUAL", Arrays.asList("budgetTier", p)));
		}
	}

	@Test
	public void unknownTierIsRejected()
	{
		expectReason(OptimizerException.Reason.BAD_REQUEST,
				() -> facade.getSuggestedCart("current", "25000", Collections.singletonList("budgetTier")));
	}
}
