package com.purchaseoptimizer.exception;

/** Business exception carrying a stable error code. */
public class OptimizerException extends RuntimeException
{
	public enum Reason
	{
		DISABLED, ACCESS_DENIED, NO_B2B_UNIT, BAD_REQUEST
	}

	private final Reason reason;

	public OptimizerException(final Reason reason, final String message)
	{
		super(message);
		this.reason = reason;
	}

	public Reason getReason()
	{
		return reason;
	}
}
