package com.purchaseoptimizer.service;

import java.util.ArrayList;
import java.util.List;

import com.purchaseoptimizer.enums.BudgetTier;


public class OptimizerResult
{
	private final List<SuggestionCandidate> lines = new ArrayList<>();
	private BudgetTier appliedTier = BudgetTier.USUAL;
	private String profileSource = "EXPLORATORY";
	private Double orderSizeValue;
	private boolean aiUsed;
	private boolean fallbackUsed;
	private String reasonCode = "OK";
	private String auditId;

	public List<SuggestionCandidate> getLines()
	{
		return lines;
	}

	public BudgetTier getAppliedTier()
	{
		return appliedTier;
	}

	public void setAppliedTier(final BudgetTier appliedTier)
	{
		this.appliedTier = appliedTier;
	}

	public String getProfileSource()
	{
		return profileSource;
	}

	public void setProfileSource(final String profileSource)
	{
		this.profileSource = profileSource;
	}

	public Double getOrderSizeValue()
	{
		return orderSizeValue;
	}

	public void setOrderSizeValue(final Double orderSizeValue)
	{
		this.orderSizeValue = orderSizeValue;
	}

	public boolean isAiUsed()
	{
		return aiUsed;
	}

	public void setAiUsed(final boolean aiUsed)
	{
		this.aiUsed = aiUsed;
	}

	public boolean isFallbackUsed()
	{
		return fallbackUsed;
	}

	public void setFallbackUsed(final boolean fallbackUsed)
	{
		this.fallbackUsed = fallbackUsed;
	}

	public String getReasonCode()
	{
		return reasonCode;
	}

	public void setReasonCode(final String reasonCode)
	{
		this.reasonCode = reasonCode;
	}

	public String getAuditId()
	{
		return auditId;
	}

	public void setAuditId(final String auditId)
	{
		this.auditId = auditId;
	}
}
