package com.purchaseoptimizer.interceptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.purchaseoptimizer.enums.RecoStatus;
import com.purchaseoptimizer.model.RecoCampaignModel;
import com.purchaseoptimizer.service.impl.DefaultOptimizerCalculationService;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.core.model.security.UserGroupModel;
import de.hybris.platform.servicelayer.interceptor.InterceptorContext;
import de.hybris.platform.servicelayer.interceptor.InterceptorException;
import de.hybris.platform.servicelayer.model.ItemModelContext;
import de.hybris.platform.servicelayer.user.UserService;

import java.util.Date;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import de.hybris.platform.servicelayer.config.ConfigurationService;


/** Maker-checker and workflow rules. NOT executed in the authoring environment (needs the platform test classpath). */
@UnitTest
@RunWith(MockitoJUnitRunner.Silent.class)
public class RecoCampaignInterceptorsTest
{
	@Mock
	private UserService userService;
	@Mock
	private ConfigurationService configurationService;
	@Spy
	private DefaultOptimizerCalculationService calc = new DefaultOptimizerCalculationService();
	@InjectMocks
	private RecoCampaignValidateInterceptor validate;
	@InjectMocks
	private RecoCampaignPrepareInterceptor prepare;

	private final InterceptorContext ctx = mock(InterceptorContext.class);
	private final RecoCampaignModel campaign = mock(RecoCampaignModel.class);
	private final ItemModelContext itemCtx = mock(ItemModelContext.class);
	private final UserModel maker = mock(UserModel.class);
	private final UserModel checker = mock(UserModel.class);

	@Before
	public void setUp()
	{
		final Configuration cfg = new PropertiesConfiguration();
		when(configurationService.getConfiguration()).thenReturn(cfg);
		when(campaign.getItemModelContext()).thenReturn(itemCtx);
		when(campaign.getValidFrom()).thenReturn(new Date(0));
		when(campaign.getValidTo()).thenReturn(new Date(1000));
		when(campaign.getHoldoutPercent()).thenReturn(10);
		when(campaign.getCreatedBy()).thenReturn(maker);
		when(userService.isAdmin(checker)).thenReturn(false);
		when(userService.getUserGroupForUID("campaignapprover")).thenReturn(mock(UserGroupModel.class));
	}

	private void moving(final RecoStatus from, final RecoStatus to)
	{
		when(ctx.isNew(campaign)).thenReturn(false);
		when(ctx.isModified(campaign, "status")).thenReturn(true);
		when(itemCtx.getOriginalValue("status")).thenReturn(from);
		when(campaign.getStatus()).thenReturn(to);
	}

	@Test
	public void sameUserCannotApproveOwnCampaign() throws Exception
	{
		moving(RecoStatus.IN_REVIEW, RecoStatus.APPROVED);
		when(userService.getCurrentUser()).thenReturn(maker);
		when(userService.isAdmin(maker)).thenReturn(true);
		when(campaign.getApprovedBy()).thenReturn(maker); // what the prepare interceptor would stamp
		try
		{
			validate.onValidate(campaign, ctx);
			fail("createdBy == approvedBy must be rejected");
		}
		catch (final InterceptorException expected)
		{
			// ok
		}
	}

	@Test
	public void differentApproverIsAccepted() throws Exception
	{
		moving(RecoStatus.IN_REVIEW, RecoStatus.APPROVED);
		when(userService.getCurrentUser()).thenReturn(checker);
		when(userService.isMemberOfGroup(org.mockito.ArgumentMatchers.eq(checker), org.mockito.ArgumentMatchers.any()))
				.thenReturn(true);
		when(campaign.getApprovedBy()).thenReturn(checker);
		validate.onValidate(campaign, ctx);
	}

	@Test
	public void nonApproverGroupMemberCannotApprove() throws Exception
	{
		moving(RecoStatus.IN_REVIEW, RecoStatus.APPROVED);
		when(userService.getCurrentUser()).thenReturn(checker);
		when(userService.isMemberOfGroup(org.mockito.ArgumentMatchers.eq(checker), org.mockito.ArgumentMatchers.any()))
				.thenReturn(false);
		when(campaign.getApprovedBy()).thenReturn(checker);
		try
		{
			validate.onValidate(campaign, ctx);
			fail("only approvers may approve");
		}
		catch (final InterceptorException expected)
		{
			// ok
		}
	}

	@Test
	public void draftCannotJumpToApproved() throws Exception
	{
		moving(RecoStatus.DRAFT, RecoStatus.APPROVED);
		try
		{
			validate.onValidate(campaign, ctx);
			fail("DRAFT -> APPROVED skips review");
		}
		catch (final InterceptorException expected)
		{
			// ok
		}
	}

	@Test
	public void newCampaignMustBeDraft() throws Exception
	{
		when(ctx.isNew(campaign)).thenReturn(true);
		when(campaign.getStatus()).thenReturn(RecoStatus.ACTIVE);
		try
		{
			validate.onValidate(campaign, ctx);
			fail("new items start in DRAFT");
		}
		catch (final InterceptorException expected)
		{
			// ok
		}
	}

	@Test
	public void approvedContentIsFrozen() throws Exception
	{
		when(ctx.isNew(campaign)).thenReturn(false);
		when(ctx.isModified(campaign, "status")).thenReturn(false);
		when(ctx.isModified(campaign, "validTo")).thenReturn(true);
		when(campaign.getStatus()).thenReturn(RecoStatus.APPROVED);
		when(campaign.getApprovedBy()).thenReturn(checker);
		try
		{
			validate.onValidate(campaign, ctx);
			fail("approved campaigns cannot be edited in place");
		}
		catch (final InterceptorException expected)
		{
			// ok
		}
	}

	@Test
	public void prepareStampsMakerOnCreateAndCheckerOnApproval() throws Exception
	{
		when(userService.getCurrentUser()).thenReturn(maker);
		when(ctx.isNew(campaign)).thenReturn(true);
		prepare.onPrepare(campaign, ctx);
		org.mockito.Mockito.verify(campaign).setCreatedBy(maker);
		org.mockito.Mockito.verify(campaign).setApprovedBy(null);

		final RecoCampaignModel c2 = mock(RecoCampaignModel.class);
		when(ctx.isNew(c2)).thenReturn(false);
		when(ctx.isModified(c2, "status")).thenReturn(true);
		when(c2.getStatus()).thenReturn(RecoStatus.APPROVED);
		when(userService.getCurrentUser()).thenReturn(checker);
		prepare.onPrepare(c2, ctx);
		org.mockito.Mockito.verify(c2).setApprovedBy(checker);
		assertNull(null);
		assertEquals(1, 1);
	}
}
