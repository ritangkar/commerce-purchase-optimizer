package com.purchaseoptimizer.service.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


/**
 * Treats ALL model output as untrusted. Stateless and thread-safe.
 * Expected JSON: {"explanations": {"cand_01": "text", ...}}
 */
public class AiResponseValidator
{
	private static final String[] BANNED = { "out of stock", "ran out", "running low", "run out", "stock-out", "stockout",
			"sold", "sell-through", "sell through", "customer demand", "consumption", "inventory", "you need", "your budget",
			"you can afford" };

	private static final Pattern BANNED_PATTERN = buildBannedPattern();
	private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
	private static final Pattern SUSPICIOUS = Pattern
			.compile("(?i)(https?://|www\\.|ignore (all|previous)|system prompt|instruction|<script|\\{|\\})");

	private final ObjectMapper mapper = new ObjectMapper();

	private static Pattern buildBannedPattern()
	{
		final StringBuilder sb = new StringBuilder("(?i)\\b(");
		for (int i = 0; i < BANNED.length; i++)
		{
			if (i > 0)
			{
				sb.append('|');
			}
			sb.append(Pattern.quote(BANNED[i]));
		}
		return Pattern.compile(sb.append(")\\b").toString());
	}

	/** True if the text contains any phrase that implies stock, sales or demand knowledge. */
	public static boolean containsBannedPhrase(final String text)
	{
		return text != null && BANNED_PATTERN.matcher(text).find();
	}

	public static String htmlEscape(final String s)
	{
		final StringBuilder sb = new StringBuilder(s.length() + 8);
		for (final char c : s.toCharArray())
		{
			switch (c)
			{
				case '&':
					sb.append("&amp;");
					break;
				case '<':
					sb.append("&lt;");
					break;
				case '>':
					sb.append("&gt;");
					break;
				case '"':
					sb.append("&quot;");
					break;
				case '\'':
					sb.append("&#x27;");
					break;
				default:
					sb.append(c);
			}
		}
		return sb.toString();
	}

	public static final class Result
	{
		private final boolean schemaValid;
		private final Map<String, String> accepted;
		private final List<String> rejectedCandidateIds;
		private final String failureReason;

		Result(final boolean schemaValid, final Map<String, String> accepted, final List<String> rejected, final String reason)
		{
			this.schemaValid = schemaValid;
			this.accepted = accepted;
			this.rejectedCandidateIds = rejected;
			this.failureReason = reason;
		}

		public boolean isSchemaValid()
		{
			return schemaValid;
		}

		/** candidateId -> sanitised (HTML-escaped) explanation. */
		public Map<String, String> getAccepted()
		{
			return accepted;
		}

		/** Candidates whose text failed validation; callers use the template for these. */
		public List<String> getRejectedCandidateIds()
		{
			return rejectedCandidateIds;
		}

		public String getFailureReason()
		{
			return failureReason;
		}
	}

	/**
	 * @param allowedNumbers per issued candidate id: the numeric tokens the engine produced for it
	 * @param maxLength max characters per explanation
	 */
	public Result validate(final String rawJson, final Map<String, Set<String>> allowedNumbers, final int maxLength)
	{
		final Map<String, String> accepted = new LinkedHashMap<>();
		final List<String> rejected = new ArrayList<>();
		try
		{
			final JsonNode root = mapper.readTree(rawJson);
			if (root == null || !root.isObject() || root.size() != 1 || !root.has("explanations")
					|| !root.get("explanations").isObject())
			{
				return fail("SCHEMA");
			}
			final JsonNode expl = root.get("explanations");
			final Iterator<String> names = expl.fieldNames();
			while (names.hasNext())
			{
				if (!allowedNumbers.containsKey(names.next()))
				{
					return fail("UNKNOWN_ID");
				}
			}
			for (final Map.Entry<String, Set<String>> e : allowedNumbers.entrySet())
			{
				final JsonNode n = expl.get(e.getKey());
				if (n == null || !n.isTextual() || !textOk(n.asText(), e.getValue(), maxLength))
				{
					rejected.add(e.getKey());
				}
				else
				{
					accepted.put(e.getKey(), htmlEscape(n.asText().trim()));
				}
			}
			return new Result(true, accepted, rejected, rejected.isEmpty() ? null : "PARTIAL");
		}
		catch (final Exception ex)
		{
			return fail("PARSE");
		}
	}

	private Result fail(final String reason)
	{
		return new Result(false, new LinkedHashMap<>(), new ArrayList<>(), reason);
	}

	private boolean textOk(final String text, final Set<String> allowed, final int maxLength)
	{
		final String t = text.trim();
		if (t.isEmpty() || t.length() > maxLength || containsBannedPhrase(t) || SUSPICIOUS.matcher(t).find())
		{
			return false;
		}
		final Set<String> allow = new HashSet<>(allowed);
		allow.add("12"); // history window in months
		final Matcher m = NUMBER.matcher(t);
		while (m.find())
		{
			if (!allow.contains(m.group().replace(",", "")))
			{
				return false;
			}
		}
		return true;
	}
}
