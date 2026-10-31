package com.purchaseoptimizer.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.purchaseoptimizer.enums.RecoStatus;
import com.purchaseoptimizer.model.RecoBundleModel;
import com.purchaseoptimizer.model.RecoCampaignModel;
import com.purchaseoptimizer.model.StrategicPriorityModel;
import com.purchaseoptimizer.service.OptimizerStrategyService;

import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.core.model.security.UserGroupModel;
import de.hybris.platform.core.model.user.EmployeeModel;
import de.hybris.platform.servicelayer.exceptions.ModelSavingException;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;

import javax.annotation.Resource;

import org.junit.Before;
import org.junit.Test;


/** Real interceptors, real users: maker-checker, workflow, frozen content, lifecycle job. NOT executed in the authoring environment. */
@IntegrationTest
public class RecoGovernanceIntegrationTest extends AbstractOptimizerIntegrationTest
{
	@Resource(name = "optimizerStrategyService")
	private OptimizerStrategyService strategyService;

	private EmployeeModel maker;
	private EmployeeModel checker;
	private EmployeeModel outsider;

	@Before
	public void setUpUsers()
	{
		final UserGroupModel approvers = modelService.create(UserGroupModel.class);
		approvers.setUid("campaignapprover");
		modelService.save(approvers);

		maker = employee("maker");
		checker = employee("checker");
		outsider = employee("outsider");
		checker.setGroups(new HashSet<>(Arrays.asList(approvers)));
		modelService.save(checker);
	}

	private EmployeeModel employee(final String uid)
	{
		final EmployeeModel e = modelService.create(EmployeeModel.class);
		e.setUid(uid);
		e.setName(uid);
		modelService.save(e);
		return e;
	}

	private RecoCampaignModel draftCampaign(final String code)
	{
		userService.setCurrentUser(maker);
		final RecoCampaignModel c = modelService.create(RecoCampaignModel.class);
		c.setCode(code);
		c.setValidFrom(new Date(System.currentTimeMillis() - DAY));
		c.setValidTo(new Date(System.currentTimeMillis() + DAY));
		c.setHoldoutPercent(10);
		c.setStatus(RecoStatus.DRAFT);
		modelService.save(c);
		return c;
	}

	private void moveTo(final RecoCampaignModel c, final RecoStatus status)
	{
		c.setStatus(status);
		modelService.save(c);
	}

	private void assertSaveRejected(final Runnable change)
	{
		try
		{
			change.run();
			fail("the save should have been rejected by an interceptor");
		}
		catch (final ModelSavingException expected)
		{
			// ok
		}
	}

	@Test
	public void makerIsStampedOnCreateAndCheckerOnApproval()
	{
		final RecoCampaignModel c = draftCampaign("GOV1");
		assertEquals(maker, c.getCreatedBy());
		assertNull(c.getApprovedBy());

		moveTo(c, RecoStatus.IN_REVIEW);
		userService.setCurrentUser(checker);
		c.setApprovedBy(maker); // attempt to forge the approver
		moveTo(c, RecoStatus.APPROVED);

		assertEquals(checker, c.getApprovedBy()); // overwritten from the session
		assertEquals(maker, c.getCreatedBy());
	}

	@Test
	public void creatorCannotApproveOwnCampaignEvenInApproverGroup()
	{
		final RecoCampaignModel c = draftCampaign("GOV2");
		moveTo(c, RecoStatus.IN_REVIEW);
		maker.setGroups(new HashSet<>(Arrays.asList((UserGroupModel) userService.getUserGroupForUID("campaignapprover"))));
		modelService.save(maker);
		assertSaveRejected(() -> moveTo(c, RecoStatus.APPROVED)); // createdBy == approvedBy
	}

	@Test
	public void userOutsideTheApproverGroupCannotApprove()
	{
		final RecoCampaignModel c = draftCampaign("GOV3");
		moveTo(c, RecoStatus.IN_REVIEW);
		userService.setCurrentUser(outsider);
		assertSaveRejected(() -> moveTo(c, RecoStatus.APPROVED));
	}

	@Test
	public void workflowCannotBeSkippedAndNewItemsStartInDraft()
	{
		final RecoCampaignModel c = draftCampaign("GOV4");
		assertSaveRejected(() -> moveTo(c, RecoStatus.APPROVED)); // DRAFT -> APPROVED skips review
		assertSaveRejected(() -> moveTo(c, RecoStatus.ACTIVE));

		final RecoCampaignModel fresh = modelService.create(RecoCampaignModel.class);
		fresh.setCode("GOV4B");
		fresh.setValidFrom(new Date());
		fresh.setValidTo(new Date(System.currentTimeMillis() + DAY));
		fresh.setStatus(RecoStatus.ACTIVE);
		assertSaveRejected(() -> modelService.save(fresh));
	}

	@Test
	public void approvedContentIsFrozenUntilSentBackToDraft()
	{
		final RecoCampaignModel c = draftCampaign("GOV5");
		moveTo(c, RecoStatus.IN_REVIEW);
		userService.setCurrentUser(checker);
		moveTo(c, RecoStatus.APPROVED);

		c.setValidTo(new Date(System.currentTimeMillis() + 5 * DAY));
		assertSaveRejected(() -> modelService.save(c));
		modelService.refresh(c);

		moveTo(c, RecoStatus.DRAFT); // sent back: editable again, must be re-approved
		c.setValidTo(new Date(System.currentTimeMillis() + 5 * DAY));
		modelService.save(c);
	}

	@Test
	public void invalidCampaignValuesAreRejected()
	{
		final RecoCampaignModel c = draftCampaign("GOV6");
		c.setHoldoutPercent(101);
		assertSaveRejected(() -> modelService.save(c));
		modelService.refresh(c);

		c.setValidTo(new Date(c.getValidFrom().getTime() - 1000));
		assertSaveRejected(() -> modelService.save(c));
	}

	@Test
	public void priorityNeedsExactlyOneTargetAndBoundedBoost()
	{
		final ProductModel p = newProduct("GOVP", 100, 6, true);
		final StrategicPriorityModel both = priority("PR_BOTH", 0.1);
		both.setProduct(p);
		both.setCategory(category);
		assertSaveRejected(() -> modelService.save(both));

		final StrategicPriorityModel none = priority("PR_NONE", 0.1);
		assertSaveRejected(() -> modelService.save(none));

		final StrategicPriorityModel tooHigh = priority("PR_HIGH", 0.9); // above purchaseoptimizer.boost.hard.max (0.5)
		tooHigh.setProduct(p);
		assertSaveRejected(() -> modelService.save(tooHigh));

		final StrategicPriorityModel ok = priority("PR_OK", 0.1);
		ok.setProduct(p);
		modelService.save(ok);
	}

	private StrategicPriorityModel priority(final String code, final double boost)
	{
		final StrategicPriorityModel p = modelService.create(StrategicPriorityModel.class);
		p.setCode(code);
		p.setBoost(boost);
		p.setStatus(RecoStatus.DRAFT);
		p.setValidFrom(new Date(System.currentTimeMillis() - DAY));
		p.setValidTo(new Date(System.currentTimeMillis() + DAY));
		return p;
	}

	@Test
	public void bundleCannotLeaveDraftWithoutEntries()
	{
		final RecoBundleModel b = modelService.create(RecoBundleModel.class);
		b.setCode("BUN_EMPTY");
		b.setStatus(RecoStatus.DRAFT);
		b.setValidFrom(new Date(System.currentTimeMillis() - DAY));
		b.setValidTo(new Date(System.currentTimeMillis() + DAY));
		modelService.save(b);

		b.setStatus(RecoStatus.IN_REVIEW);
		assertSaveRejected(() -> modelService.save(b));
	}

	@Test
	public void lifecycleJobActivatesThenExpiresByDate()
	{
		final RecoCampaignModel c = draftCampaign("GOV7");
		moveTo(c, RecoStatus.IN_REVIEW);
		userService.setCurrentUser(checker);
		moveTo(c, RecoStatus.APPROVED);

		userService.setCurrentUser(userService.getAdminUser());
		strategyService.runLifecycle(new Date());
		modelService.refresh(c);
		assertEquals(RecoStatus.ACTIVE, c.getStatus());

		strategyService.runLifecycle(new Date(System.currentTimeMillis() + 3 * DAY)); // after validTo
		modelService.refresh(c);
		assertEquals(RecoStatus.EXPIRED, c.getStatus());
	}

	@Test
	public void lifecycleDoesNotActivateBeforeValidFrom()
	{
		final RecoCampaignModel c = draftCampaign("GOV8");
		c.setValidFrom(new Date(System.currentTimeMillis() + 2 * DAY));
		c.setValidTo(new Date(System.currentTimeMillis() + 4 * DAY));
		modelService.save(c);
		moveTo(c, RecoStatus.IN_REVIEW);
		userService.setCurrentUser(checker);
		moveTo(c, RecoStatus.APPROVED);

		userService.setCurrentUser(userService.getAdminUser());
		strategyService.runLifecycle(new Date());
		modelService.refresh(c);
		assertEquals(RecoStatus.APPROVED, c.getStatus());
	}
}
