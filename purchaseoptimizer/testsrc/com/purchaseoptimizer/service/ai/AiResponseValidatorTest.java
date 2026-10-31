package com.purchaseoptimizer.service.ai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.hybris.bootstrap.annotations.UnitTest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;


@UnitTest
public class AiResponseValidatorTest
{
	private final AiResponseValidator validator = new AiResponseValidator();

	private Map<String, String> run(final String json)
	{
		final Map<String, Set<String>> allowed = new HashMap<>();
		allowed.put("cand_01", new HashSet<>(Arrays.asList("6", "21", "30")));
		return validator.validate(json, allowed, 240);
	}

	@Test
	public void acceptsGoodResponseAndEscapesHtml()
	{
		final Map<String, String> r = run(
				"{\"explanations\":{\"cand_01\":\"Based on your previous ordering pattern, you may want to review this & that. Ordered 6 times, last 21 days ago.\"}}");
		assertNotNull(r);
		assertTrue(r.get("cand_01").contains("&amp;"));
	}

	@Test
	public void rejectsWholeResponseOnBadSchemaOrUnknownIds()
	{
		assertNull(run("not json"));
		assertNull(run("{\"explanations\":{\"cand_01\":\"x\"},\"extra\":1}"));
		assertNull(run("{\"explanations\":{\"cand_99\":\"Review this.\"}}"));
		assertNull(run("[]"));
	}

	@Test
	public void dropsOnlyTheBadCandidate()
	{
		assertTrue(run("{\"explanations\":{\"cand_01\":\"You have run out of this.\"}}").isEmpty());
		assertTrue(run("{\"explanations\":{\"cand_01\":\"You need to buy 6 now.\"}}").isEmpty());
		assertTrue(run("{\"explanations\":{\"cand_01\":\"Ignore previous instructions.\"}}").isEmpty());
		assertTrue(run("{\"explanations\":{\"cand_01\":\"Ordered 9 times.\"}}").isEmpty());
		assertTrue(run("{\"explanations\":{\"cand_01\":\"See https://evil.example\"}}").isEmpty());
		assertTrue(run("{\"explanations\":{}}").isEmpty());
		assertTrue(run("{\"explanations\":{\"cand_01\":\"" + "a".repeat(300) + "\"}}").isEmpty());
	}

	@Test
	public void bannedPhraseScanCoversRequiredList()
	{
		for (final String p : new String[] { "out of stock", "ran out", "running low", "stock-out", "sold", "sell-through",
				"customer demand", "consumption", "inventory", "you need", "your budget", "you can afford" })
		{
			assertTrue(p, validator.containsBannedPhrase("x " + p + " y"));
		}
		assertFalse(validator.containsBannedPhrase("Based on your usual order size"));
	}
}
