package com.nibtracker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class NibTrackerApiTest
{
	@Test
	public void reportsTheReleaseVersion() throws Exception
	{
		Properties p = new Properties();
		try (InputStream in = new FileInputStream("runelite-plugin.properties"))
		{
			p.load(in);
		}
		assertEquals(p.getProperty("version"), NibTrackerApi.VERSION);
	}

	@Test
	public void sendsTheKeyAsAHeaderAndTheBodyWithoutPrefixes() throws Exception
	{
		String[] seen = new String[3];
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
			seen[0] = chain.request().url().toString();
			seen[1] = chain.request().header("X-BT-Key");
			okio.Buffer b = new okio.Buffer();
			chain.request().body().writeTo(b);
			seen[2] = b.readUtf8();
			return reply(chain.request(), 200, "{\"ok\":true,\"accepted\":0}");
		}).build();
		assertEquals("true", ingest(http).get("ok").getAsString());
		assertEquals("https://nibtracker.com/api/plugin/v1/ingest", seen[0]);
		assertEquals("btk_test", seen[1]);
		assertEquals("{\"who\":{\"rsn\":\"Zezima\"},\"events\":[]}", seen[2]);
	}

	@Test
	public void namesTheCallsThatCanNeverSucceed() throws Exception
	{
		assertEquals("bad_request", ingest(answering(400)).get("error").getAsString());
		assertEquals("too_big", ingest(answering(413)).get("error").getAsString());
		assertNull(ingest(answering(502))); // worth trying again later
	}

	@Test
	public void halvesATooBigBatchUntilOneEventIsDropped()
	{
		assertEquals(25, NibTrackerPlugin.smallerBatch(50));
		assertEquals(1, NibTrackerPlugin.smallerBatch(3));
		assertEquals(1, NibTrackerPlugin.smallerBatch(2));
		assertEquals(0, NibTrackerPlugin.smallerBatch(1));
		int n = 50, tries = 0;
		while (n > 0)
		{
			n = NibTrackerPlugin.smallerBatch(n);
			tries++;
		}
		assertEquals(6, tries); // 50, 25, 12, 6, 3, 1, then dropped
	}

	private static OkHttpClient answering(int code)
	{
		return new OkHttpClient.Builder().addInterceptor(chain -> reply(chain.request(), code, "{\"ok\":false}")).build();
	}

	private static Response reply(okhttp3.Request req, int code, String body)
	{
		return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("")
			.body(ResponseBody.create(MediaType.parse("application/json"), body)).build();
	}

	private static JsonObject ingest(OkHttpClient http) throws Exception
	{
		JsonObject who = new JsonObject();
		who.addProperty("rsn", "Zezima");
		CompletableFuture<JsonObject> done = new CompletableFuture<>();
		new NibTrackerApi(http, new Gson()).ingest("btk_test", who, new JsonArray(), done::complete);
		return done.get(5, TimeUnit.SECONDS);
	}
}
