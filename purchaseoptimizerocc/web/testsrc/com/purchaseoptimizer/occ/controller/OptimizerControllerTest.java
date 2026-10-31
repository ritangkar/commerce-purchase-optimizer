package com.purchaseoptimizer.occ.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.purchaseoptimizer.data.OptimizerResultData;
import com.purchaseoptimizer.data.SuggestionCandidateData;
import com.purchaseoptimizer.enums.BudgetTier;
import com.purchaseoptimizer.enums.CandidateGroup;
import com.purchaseoptimizer.enums.ConfidenceTier;
import com.purchaseoptimizer.facade.impl.DefaultOptimizerFacade;
import com.purchaseoptimizer.service.OptimizerService;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.b2b.model.B2BCustomerModel;
import de.hybris.platform.b2b.model.B2BUnitModel;
import de.hybris.platform.b2b.services.B2BUnitService;
import de.hybris.platform.commercefacades.product.ProductFacade;
import de.hybris.platform.commercefacades.product.data.ProductData;
import de.hybris.platform.commercewebservicescommons.dto.product.ProductWsDTO;
import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.user.UserService;
import de.hybris.platform.webservicescommons.mapping.DataMapper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.configuration.PropertiesConfiguration;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.annotation.Secured;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;


/**
 * Controller plus the REAL facade over mocked services (standalone MockMvc). Covers input rules, dealer isolation, error
 * shape and grouping. The OAuth filter chain itself is NOT part of this test - see docs/occ-smoke-tests.md.
 * NOT executed in the authoring environment.
 */
@UnitTest
public class OptimizerControllerTest
{
	private static final String URL = "/electronics/users/{userId}/optimizer/suggested-cart";

	private final OptimizerService optimizerService = mock(OptimizerService.class);
	private final UserService userService = mock(UserService.class);
	@SuppressWarnings("unchecked")
	private final B2BUnitService<B2BUnitModel, B2BCustomerModel> b2bUnitService = mock(B2BUnitService.class);
	private final ProductFacade productFacade = mock(ProductFacade.class);
	private final ConfigurationService configurationService = mock(ConfigurationService.class);
	private final DataMapper dataMapper = mock(DataMapper.class);
	private final B2BCustomerModel customer = mock(B2BCustomerModel.class);
	private final PropertiesConfiguration config = new PropertiesConfiguration();
	private MockMvc mvc;

	@Before
	public void setUp()
	{
		when(configurationService.getConfiguration()).thenReturn(config);
		when(customer.getUid()).thenReturn("dealer@example.com");
		when(userService.getCurrentUser()).thenReturn(customer);
		when(b2bUnitService.getParent(customer)).thenReturn(mock(B2BUnitModel.class));
		when(optimizerService.generateSuggestedCart(any(), any(), any())).thenReturn(result());

		final DefaultOptimizerFacade facade = new DefaultOptimizerFacade();
		ReflectionTestUtils.setField(facade, "optimizerService", optimizerService);
		ReflectionTestUtils.setField(facade, "userService", userService);
		ReflectionTestUtils.setField(facade, "b2bUnitService", b2bUnitService);
		ReflectionTestUtils.setField(facade, "productFacade", productFacade);
		ReflectionTestUtils.setField(facade, "configurationService", configurationService);

		final OptimizerController controller = new OptimizerController();
		ReflectionTestUtils.setField(controller, "optimizerFacade", facade);
		ReflectionTestUtils.setField(controller, "dataMapper", dataMapper);
		mvc = MockMvcBuilders.standaloneSetup(controller).build();

		when(productFacade.getProductForCodeAndOptions(anyString(), any())).thenAnswer(inv -> {
			final ProductData p = new ProductData();
			p.setCode(inv.getArgument(0));
			return p;
		});
		when(dataMapper.mapAsList(any(), eq(ProductWsDTO.class), anyString())).thenAnswer(inv -> {
			final List<ProductWsDTO> out = new ArrayList<>();
			for (final Object o : (Iterable<?>) inv.getArgument(0))
			{
				final ProductWsDTO dto = new ProductWsDTO();
				dto.setCode(((ProductData) o).getCode());
				out.add(dto);
			}
			return out;
		});
	}

	private OptimizerResultData result()
	{
		final OptimizerResultData r = new OptimizerResultData();
		r.setAppliedTier(BudgetTier.USUAL);
		r.setLines(new ArrayList<>(Arrays.asList(line("P1", CandidateGroup.PATTERN), line("S1", CandidateGroup.STRATEGIC),
				line("B1", CandidateGroup.BUNDLE_CAMPAIGN))));
		return r;
	}

	private SuggestionCandidateData line(final String code, final CandidateGroup group)
	{
		final SuggestionCandidateData c = new SuggestionCandidateData();
		c.setProductCode(code);
		c.setGroup(group);
		c.setConfidenceTier(ConfidenceTier.MEDIUM);
		c.setSuggestedQuantity(12);
		c.setExplanation("text");
		c.setExplanationSource("TEMPLATE");
		c.setEvidenceLabels(new ArrayList<>());
		return c;
	}

	// ------------------------------------------------------------------ success and grouping
	@Test
	public void validRequestReturnsProductsForCurrentUser() throws Exception
	{
		final MvcResult r = mvc.perform(get(URL, "current")).andReturn();
		assertEquals(200, r.getResponse().getStatus());
		assertTrue(r.getResponse().getContentAsString().contains("\"products\""));
	}

	@Test
	public void ownUidAndOccStandardParametersAreAccepted() throws Exception
	{
		assertEquals(200, mvc.perform(get(URL, "dealer@example.com").param("lang", "en").param("curr", "INR").param("budgetTier", "usual"))
				.andReturn().getResponse().getStatus());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void patternStrategicAndBundleItemsStayInSeparateLabelledGroups() throws Exception
	{
		mvc.perform(get(URL, "current"));
		final ArgumentCaptor<Iterable> captor = ArgumentCaptor.forClass(Iterable.class);
		verify(dataMapper).mapAsList(captor.capture(), eq(ProductWsDTO.class), anyString());
		final List<String> groups = new ArrayList<>();
		for (final Object o : captor.getValue())
		{
			groups.add(((ProductData) o).getCode() + "=" + ((ProductData) o).getSuggestionGroup());
		}
		assertEquals(Arrays.asList("P1=PATTERN", "S1=STRATEGIC", "B1=BUNDLE_CAMPAIGN"), groups);
	}

	// ------------------------------------------------------------------ the dealer cannot steer the engine
	@Test
	public void amountBoostAndCampaignParametersAreRejected() throws Exception
	{
		for (final String p : Arrays.asList("budget", "budgetAmount", "amount", "maxAmount", "boost", "campaign", "campaignCode",
				"holdout", "segment"))
		{
			assertEquals(p, 400, mvc.perform(get(URL, "current").param(p, "5000")).andReturn().getResponse().getStatus());
		}
		verify(optimizerService, never()).generateSuggestedCart(any(), any(), any());
	}

	@Test
	public void unknownTierIsRejected() throws Exception
	{
		assertEquals(400, mvc.perform(get(URL, "current").param("budgetTier", "5000")).andReturn().getResponse().getStatus());
	}

	// ------------------------------------------------------------------ dealer isolation
	@Test
	public void anotherUsersIdIsForbidden() throws Exception
	{
		assertEquals(403, mvc.perform(get(URL, "someone.else@example.com")).andReturn().getResponse().getStatus());
		verify(optimizerService, never()).generateSuggestedCart(any(), any(), any());
	}

	@Test
	public void anonymousAndNonB2BSessionsAreForbidden() throws Exception
	{
		final UserModel anon = mock(UserModel.class);
		when(userService.getCurrentUser()).thenReturn(anon);
		when(userService.isAnonymousUser(anon)).thenReturn(true);
		assertEquals(403, mvc.perform(get(URL, "current")).andReturn().getResponse().getStatus());
		verify(optimizerService, never()).generateSuggestedCart(any(), any(), any());
	}

	@Test
	public void customerWithoutB2BUnitGetsBadRequest() throws Exception
	{
		when(b2bUnitService.getParent(customer)).thenReturn(null);
		assertEquals(400, mvc.perform(get(URL, "current")).andReturn().getResponse().getStatus());
	}

	@Test
	public void featureFlagOffLooksLikeNoEndpoint() throws Exception
	{
		config.setProperty("purchaseoptimizer.enabled", Boolean.FALSE);
		assertEquals(404, mvc.perform(get(URL, "current")).andReturn().getResponse().getStatus());
		verify(optimizerService, never()).generateSuggestedCart(any(), any(), any());
	}

	// ------------------------------------------------------------------ error shape and no side effects
	@Test
	public void errorBodyHasTypeAndMessageOnly() throws Exception
	{
		final String body = mvc.perform(get(URL, "someone.else@example.com")).andReturn().getResponse().getContentAsString();
		assertTrue(body.contains("\"errors\"") && body.contains("\"type\"") && body.contains("\"message\""));
		assertFalse(body.contains("Exception"));
		assertFalse(body.contains(" at "));
	}

	@Test
	public void onlyGetIsExposedSoNothingCanBeCreatedOrOrdered() throws Exception
	{
		assertEquals(405, mvc.perform(post(URL, "current")).andReturn().getResponse().getStatus());
		assertEquals(405, mvc.perform(put(URL, "current")).andReturn().getResponse().getStatus());
		assertEquals(405, mvc.perform(delete(URL, "current")).andReturn().getResponse().getStatus());

		for (final Method m : OptimizerController.class.getDeclaredMethods())
		{
			final RequestMapping rm = m.getAnnotation(RequestMapping.class);
			if (rm != null)
			{
				assertEquals(Arrays.asList(RequestMethod.GET), Arrays.asList(rm.method()));
			}
		}
	}

	// ------------------------------------------------------------------ scope declaration
	@Test
	public void endpointIsSecuredForCustomerAndTrustedClientOnly() throws Exception
	{
		Method endpoint = null;
		for (final Method m : OptimizerController.class.getDeclaredMethods())
		{
			if (m.getName().equals("getSuggestedCart"))
			{
				endpoint = m;
			}
		}
		final Secured secured = endpoint.getAnnotation(Secured.class);
		final Set<String> roles = new HashSet<>(Arrays.asList(secured.value()));
		assertEquals(new HashSet<>(Arrays.asList("ROLE_CUSTOMERGROUP", "ROLE_TRUSTED_CLIENT")), roles);
		assertFalse(roles.contains("ROLE_ANONYMOUS"));
	}
}
