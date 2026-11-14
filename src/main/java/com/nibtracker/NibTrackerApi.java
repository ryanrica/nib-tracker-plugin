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
 * Calls Nib Tracker's plugin API v1 (hello, ingest, screenshot) on the site itself.
 * Every call runs on OkHttp's thread pool; callbacks get the parsed reply, or null when the call failed and is worth
 * trying again. A 400 (a call that can never succeed) comes back as a reply with {@code error: "bad_request"}, and a
 * 413 (body over the limit) as one with {@code error: "too_big"}.
 */
@Slf4j
@Singleton
class NibTrackerApi
{
	/* Must match runelite-plugin.properties (NibTrackerApiTest checks), so the server can tell releases apart. */
	static final String VERSION = "0.8.0";

	/* The Nib Tracker web app. Goal links in the panel open it, and the plugin API lives on it; the server checks the
	   plugin key on every call. */
	static final String SITE = "https://nibtracker.com";
	private static final String BASE = SITE + "/api/plugin/v1/";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final MediaType JPEG = MediaType.parse("image/jpeg");

	private final OkHttpClient http;
	private final Gson gson;

	@Inject
	NibTrackerApi(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	/** {@code settingsHash}: the last settings' hash, so the server can leave settings out when they haven't changed; or null. */
	void hello(String key, JsonObject who, String settingsHash, Consumer<JsonObject> done)
	{
		JsonObject body = new JsonObject();
		body.add("who", who);
		if (settingsHash != null)
		{
			body.addProperty("settingsHash", settingsHash);
		}
		post("hello", key, body, done);
	}

	void ingest(String key, JsonObject who, JsonArray events, Consumer<JsonObject> done)
	{
		JsonObject body = new JsonObject();
		body.add("who", who);
		body.add("events", events);
		post("ingest", key, body, done);
	}

	/** Uploads a drop tile proof picture for an event the server already has. Fire and forget. */
	void uploadScreenshot(String key, String eventId, byte[] jpeg)
	{
		Request req = new Request.Builder()
			.url(BASE + "screenshot")
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

	private void post(String fn, String key, JsonObject body, Consumer<JsonObject> done)
	{
		Request req = new Request.Builder()
			.url(BASE + fn)
			.header("X-BT-Key", key)
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
					if (res.code() == 400)
					{
						// The call breaks the API's rules, so sending it again would never work.
						log.debug("Nib Tracker {} refused the call: {}", fn, rb == null ? "" : rb.string());
						reply = new JsonObject();
						reply.addProperty("ok", false);
						reply.addProperty("error", "bad_request");
					}
					else if (res.code() == 413)
					{
						reply = new JsonObject();
						reply.addProperty("ok", false);
						reply.addProperty("error", "too_big");
					}
					else if (!res.isSuccessful() || rb == null)
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
