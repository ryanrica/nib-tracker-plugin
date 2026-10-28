package com.nibtracker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Calls the two Supabase functions the plugin is allowed to use (supabase/plugin.sql).
 * Every call runs on OkHttp's thread pool; callbacks get the parsed reply, or null when the call failed.
 */
@Slf4j
@Singleton
class NibTrackerApi
{
	/* Must match runelite-plugin.properties (NibTrackerApiTest checks), so the server can tell releases apart. */
	static final String VERSION = "0.6.0";

	/* The Supabase project address and its publishable key. Both are public by design (they're in the web app too);
	   the server checks the plugin key on every call. */
	private static final String BASE = "https://adndcxzfizabkuiwuzwq.supabase.co/rest/v1/rpc/";
	private static final String PUBLISHABLE_KEY = "sb_publishable_mrNOgXJ1KVgcKHjr0ucfWQ_IpI0YQqT";
	/* The Nib Tracker web app. Goal links in the panel open it; screenshots go to it because only it holds the key that
	   may write to storage. */
	static final String SITE = "https://buntracker.pages.dev";
	private static final String SCREENSHOT_URL = SITE + "/api/plugin/screenshot";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final MediaType JPEG = MediaType.parse("image/jpeg");

	@Inject
	private OkHttpClient http;

	@Inject
	private Gson gson;

	void hello(String key, JsonObject who, Consumer<JsonObject> done)
	{
		JsonObject body = new JsonObject();
		body.addProperty("p_key", key);
		body.addProperty("p_version", VERSION);
		body.add("p_who", who);
		post("bt_plugin_hello", body, done);
	}

	void ingest(String key, JsonObject who, JsonArray events, Consumer<JsonObject> done)
	{
		JsonObject body = new JsonObject();
		body.addProperty("p_key", key);
		body.addProperty("p_version", VERSION);
		body.add("p_who", who);
		body.add("p_events", events);
		post("bt_plugin_ingest", body, done);
	}

	/** Uploads a drop tile proof picture for an event the server already has. Fire and forget. */
	void uploadScreenshot(String key, String eventId, byte[] jpeg)
	{
		Request req = new Request.Builder()
			.url(SCREENSHOT_URL)
			.header("X-BT-Key", key)
			.header("X-BT-Event", eventId)
			.header("X-BT-Plugin-Version", VERSION)
			.post(RequestBody.create(JPEG, jpeg))
			.build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Nib Tracker screenshot upload failed", e);
			}

			@Override
			public void onResponse(Call call, Response res)
			{
				res.close();
			}
		});
	}

	private void post(String fn, JsonObject body, Consumer<JsonObject> done)
	{
		Request req = new Request.Builder()
			.url(BASE + fn)
			.header("apikey", PUBLISHABLE_KEY)
			.header("X-BT-Plugin-Version", VERSION)
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Nib Tracker {} failed", fn, e);
				done.accept(null);
			}

			@Override
			public void onResponse(Call call, Response res)
			{
				// Read the reply first, then hand it on once, so an error in the callback can't make it run twice.
				JsonObject reply = null;
				try (ResponseBody rb = res.body())
				{
					if (!res.isSuccessful() || rb == null)
					{
						log.debug("Nib Tracker {} returned HTTP {}", fn, res.code());
					}
					else
					{
						reply = gson.fromJson(rb.charStream(), JsonObject.class);
					}
				}
				catch (Exception e)
				{
					log.debug("Nib Tracker {} sent an unreadable reply", fn, e);
				}
				done.accept(reply);
			}
		});
	}
}
