package com.birlaopus.optimizer.enums;

public enum CandidateGroup
{
	/** Product the dealer already orders on a repeat basis. */
	PATTERN,
	/** Product linked (ProductReference) to something the dealer orders regularly, not ordered in the window. */
	AFFINITY
}
