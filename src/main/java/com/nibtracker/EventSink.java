package com.nibtracker;

import com.google.gson.JsonObject;

/** What the trackers need from the plugin. All methods are called on the client thread. */
interface EventSink
{
	/** Queues an event for the current character. Returns its id, or null if nothing was queued. */
	String emit(String type, JsonObject data);

	/** A source is sent only if both the player's toggle and the server's settings allow it. */
	boolean allowed(String serverKey, boolean local);

	/** The latest settings from the server (plain data). */
	JsonObject settings();

	/** True once a character is logged in and its stats have loaded. */
	boolean active();
}
