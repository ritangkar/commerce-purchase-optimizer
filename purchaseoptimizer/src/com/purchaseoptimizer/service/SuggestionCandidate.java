package com.purchaseoptimizer.service;

import java.util.ArrayList;
import java.util.List;

import com.purchaseoptimizer.enums.CandidateGroup;
import com.purchaseoptimizer.enums.ConfidenceTier;


/** One suggested line produced by the deterministic engine. Mutable only inside the service package. */
public class SuggestionCandidate
{
	private String candidateId;
	private String productCode;
	private CandidateGroup group;
	private ConfidenceTier confidenceTier;
	private int orderCount12m;
	private long daysSinceLastOrder;
	private long medianIntervalDays;
	private String dueState; // APPROACHING_TYPICAL_PERIOD / BEYOND_TYPICAL_PERIOD / CATALOGUE_LINK
	private long suggestedQuantity;
	private long packSize = 1;
	private long minQuantity = 1;
	private double unitPrice; // internal only: never exposed, never sent to AI
	private double relevance;
	private double evidenceScore;
	private String explanation;
	private String explanationSource = "TEMPLATE";
	private final List<String> evidenceLabels = new ArrayList<>();

	public String getCandidateId()
	{
		return candidateId;
	}

	void setCandidateId(final String candidateId)
	{
		this.candidateId = candidateId;
	}

	public String getProductCode()
	{
		return productCode;
	}

	void setProductCode(final String productCode)
	{
		this.productCode = productCode;
	}

	public CandidateGroup getGroup()
	{
		return group;
	}

	void setGroup(final CandidateGroup group)
	{
		this.group = group;
	}

	public ConfidenceTier getConfidenceTier()
	{
		return confidenceTier;
	}

	void setConfidenceTier(final ConfidenceTier confidenceTier)
	{
		this.confidenceTier = confidenceTier;
	}

	public int getOrderCount12m()
	{
		return orderCount12m;
	}

	void setOrderCount12m(final int orderCount12m)
	{
		this.orderCount12m = orderCount12m;
	}

	public long getDaysSinceLastOrder()
	{
		return daysSinceLastOrder;
	}

	void setDaysSinceLastOrder(final long daysSinceLastOrder)
	{
		this.daysSinceLastOrder = daysSinceLastOrder;
	}

	public long getMedianIntervalDays()
	{
		return medianIntervalDays;
	}

	void setMedianIntervalDays(final long medianIntervalDays)
	{
		this.medianIntervalDays = medianIntervalDays;
	}

	public String getDueState()
	{
		return dueState;
	}

	void setDueState(final String dueState)
	{
		this.dueState = dueState;
	}

	public long getSuggestedQuantity()
	{
		return suggestedQuantity;
	}

	void setSuggestedQuantity(final long suggestedQuantity)
	{
		this.suggestedQuantity = suggestedQuantity;
	}

	public long getPackSize()
	{
		return packSize;
	}

	void setPackSize(final long packSize)
	{
		this.packSize = packSize;
	}

	public long getMinQuantity()
	{
		return minQuantity;
	}

	void setMinQuantity(final long minQuantity)
	{
		this.minQuantity = minQuantity;
	}

	double getUnitPrice()
	{
		return unitPrice;
	}

	void setUnitPrice(final double unitPrice)
	{
		this.unitPrice = unitPrice;
	}

	public double getRelevance()
	{
		return relevance;
	}

	void setRelevance(final double relevance)
	{
		this.relevance = relevance;
	}

	public double getEvidenceScore()
	{
		return evidenceScore;
	}

	void setEvidenceScore(final double evidenceScore)
	{
		this.evidenceScore = evidenceScore;
	}

	public String getExplanation()
	{
		return explanation;
	}

	void setExplanation(final String explanation)
	{
		this.explanation = explanation;
	}

	public String getExplanationSource()
	{
		return explanationSource;
	}

	void setExplanationSource(final String explanationSource)
	{
		this.explanationSource = explanationSource;
	}

	public List<String> getEvidenceLabels()
	{
		return evidenceLabels;
	}
}
