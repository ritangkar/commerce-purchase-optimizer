package com.purchaseoptimizer.interceptor;

import com.purchaseoptimizer.enums.RecoStatus;
import com.purchaseoptimizer.service.OptimizerCalculationService;

import de.hybris.platform.core.model.ItemModel;
import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.interceptor.InterceptorContext;
import de.hybris.platform.servicelayer.interceptor.InterceptorException;
import de.hybris.platform.servicelayer.user.UserService;

import java.util.Date;

import javax.annotation.Resource;


/** Shared workflow rules for RecoCampaign, StrategicPriority and RecoBundle. */
public abstract class RecoGovernanceSupport
{
	@Resource(name = "userService")
	protected UserService userService;
	@Resource(name = "configurationService")
	protected ConfigurationService configurationService;
	@Resource(name = "optimizerCalculationService")
	protected OptimizerCalculationService calc;

	protected RecoStatus previousStatus(final ItemModel model)
	{
		return (RecoStatus) model.getItemModelContext().getOriginalValue("status");
	}

	/**
	 * New items must start in DRAFT. Existing items may only follow the allowed workflow moves. Moving to APPROVED
	 * requires the current user to be an approver (or admin).
	 */
	protected void checkWorkflow(final ItemModel model, final RecoStatus status, final InterceptorContext ctx)
			throws InterceptorException
	{
		if (status == null)
		{
			throw new InterceptorException("status is required");
		}
		if (ctx.isNew(model))
		{
			if (status != RecoStatus.DRAFT)
			{
				throw new InterceptorException("New items must be created in status DRAFT");
			}
			return;
		}
		if (!ctx.isModified(model, "status"))
		{
			return;
		}
		final RecoStatus from = previousStatus(model);
		if (!calc.isTransitionAllowed(from, status))
		{
			throw new InterceptorException("Status change " + from + " -> " + status + " is not allowed");
		}
		if (status == RecoStatus.APPROVED && !currentUserMayApprove())
		{
			throw new InterceptorException("Only members of the approver group may approve");
		}
	}

	/** Approved/active content is frozen: send it back to DRAFT to change it, so it must be approved again. */
	protected void lockApprovedContent(final ItemModel model, final RecoStatus status, final InterceptorContext ctx,
			final String... contentAttributes) throws InterceptorException
	{
		if (ctx.isNew(model) || (status != RecoStatus.APPROVED && status != RecoStatus.ACTIVE))
		{
			return;
		}
		if (ctx.isModified(model, "status") && previousStatus(model) != status)
		{
			return; // the status move itself (e.g. APPROVED -> ACTIVE by the job)
		}
		for (final String attr : contentAttributes)
		{
			if (ctx.isModified(model, attr))
			{
				throw new InterceptorException("'" + attr + "' cannot be changed while " + status
						+ ". Move the item back to DRAFT and get it approved again.");
			}
		}
	}

	protected boolean currentUserMayApprove()
	{
		final UserModel user = userService.getCurrentUser();
		if (userService.isAdmin(user))
		{
			return true;
		}
		final String group = configurationService.getConfiguration().getString("purchaseoptimizer.approver.group",
				"campaignapprover");
		return userService.isMemberOfGroup(user, userService.getUserGroupForUID(group));
	}

	protected void checkDates(final Date from, final Date to) throws InterceptorException
	{
		if (from == null || to == null)
		{
			throw new InterceptorException("validFrom and validTo are required");
		}
		if (to.before(from))
		{
			throw new InterceptorException("validTo must not be before validFrom");
		}
	}
}
