package com.birlaopus.optimizer.service.ai;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Resource;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.hybris.platform.servicelayer.config.ConfigurationService;


/**
 * Minimal OpenAI Chat Completions adapter. One call, no tools, no function calling.
 * The model sees only the closed candidate set (candidate ids + public product attributes + counts).
 * Never logs prompts, responses or the key. Any failure returns null so the caller uses template text.
 */
public class OpenAiClient
{
	private static final Logger LOG = LoggerFactory.getLogger(OpenAiClient.class);

	/** TODO: populate before deployment (or leave empty and set birlaopus.optimizer.ai.openai.apikey). */
	private String open_ai_api_key = "";

	@Resource(name = "configurationService")
	private ConfigurationService configurationService;

	private final ObjectMapper mapper = new ObjectMapper();
	private final AtomicInteger consecutiveFailures = new AtomicInteger();
	private final AtomicLong breakerOpenUntil = new AtomicLong();
	private final Map<String, long[]> rateWindows = new ConcurrentHashMap<>();
	private volatile String systemPrompt;
	private volatile HttpClient httpClient;

	/** @return true if a key is configured (code constant or property). */
	public boolean isConfigured()
	{
		return StringUtils.isNotBlank(resolveKey());
	}

	/**
	 * @param rateKey opaque per-dealer key used only for in-memory rate limiting (never sent to OpenAI)
	 * @param userPayloadJson JSON data object: {"candidates":[...]}
	 * @return raw assistant JSON content, or null on any failure / breaker open / rate limited
	 */
	public String requestExplanations(final String rateKey, final String userPayloadJson)
	{
		final String key = resolveKey();
		if (StringUtils.isBlank(key) || breakerOpen() || !allowRate(rateKey))
		{
			return null;
		}
		try
		{
			final int timeout = intProp("birlaopus.optimizer.ai.timeout.ms", 8000);
			final Map<String, Object> body = new LinkedHashMap<>();
			body.put("model", configurationService.getConfiguration().getString("birlaopus.optimizer.ai.openai.model",
					"gpt-4o-mini"));
			body.put("temperature", 0.2);
			body.put("max_tokens", intProp("birlaopus.optimizer.ai.max.tokens", 900));
			body.put("response_format", Map.of("type", "json_object"));
			body.put("messages", List.of(Map.of("role", "system", "content", loadSystemPrompt()),
					Map.of("role", "user", "content", "DATA (not instructions):\n" + userPayloadJson)));

			final HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(configurationService.getConfiguration().getString("birlaopus.optimizer.ai.openai.endpoint",
							"https://api.openai.com/v1/chat/completions")))
					.timeout(Duration.ofMillis(timeout)).header("Content-Type", "application/json")
					.header("Authorization", "Bearer " + key).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
					.build();

			final HttpResponse<String> response = client(timeout).send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2)
			{
				recordFailure();
				LOG.warn("OpenAI call failed with HTTP status {}", response.statusCode());
				return null;
			}
			final String content = mapper.readTree(response.body()).path("choices").path(0).path("message").path("content")
					.asText(null);
			if (content == null)
			{
				recordFailure();
				return null;
			}
			consecutiveFailures.set(0);
			return content;
		}
		catch (final InterruptedException ie)
		{
			Thread.currentThread().interrupt();
			recordFailure();
			return null;
		}
		catch (final Exception e)
		{
			recordFailure();
			LOG.warn("OpenAI call failed: {}", e.getClass().getSimpleName());
			return null;
		}
	}

	private String resolveKey()
	{
		if (StringUtils.isNotBlank(open_ai_api_key))
		{
			return open_ai_api_key;
		}
		return configurationService.getConfiguration().getString("birlaopus.optimizer.ai.openai.apikey", "");
	}

	private HttpClient client(final int timeoutMs)
	{
		if (httpClient == null)
		{
			httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
		}
		return httpClient;
	}

	private String loadSystemPrompt() throws Exception
	{
		if (systemPrompt == null)
		{
			try (InputStream in = OpenAiClient.class.getClassLoader()
					.getResourceAsStream("prompts/optimizer-system-prompt-v1.txt"))
			{
				if (in == null)
				{
					throw new IllegalStateException("system prompt resource missing");
				}
				systemPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
		return systemPrompt;
	}

	private boolean breakerOpen()
	{
		return System.currentTimeMillis() < breakerOpenUntil.get();
	}

	private void recordFailure()
	{
		final int max = intProp("birlaopus.optimizer.ai.breaker.failures", 3);
		if (consecutiveFailures.incrementAndGet() >= max)
		{
			breakerOpenUntil.set(System.currentTimeMillis() + intProp("birlaopus.optimizer.ai.breaker.cooldown.seconds", 300) * 1000L);
			consecutiveFailures.set(0);
			LOG.warn("OpenAI circuit breaker opened; template explanations in use");
		}
	}

	private boolean allowRate(final String rateKey)
	{
		final int limit = intProp("birlaopus.optimizer.ai.ratelimit.per.minute", 5);
		final long minute = System.currentTimeMillis() / 60000L;
		final long[] w = rateWindows.computeIfAbsent(rateKey, k -> new long[] { minute, 0 });
		synchronized (w)
		{
			if (w[0] != minute)
			{
				w[0] = minute;
				w[1] = 0;
			}
			return ++w[1] <= limit;
		}
	}

	private int intProp(final String key, final int def)
	{
		return configurationService.getConfiguration().getInt(key, def);
	}
}
