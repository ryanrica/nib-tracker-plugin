package com.nibtracker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SlayerTaskIconsTest
{
	@Test
	public void namesAreLowerCaseWithoutThe() throws Exception
	{
		try (Reader r = new InputStreamReader(GoalTracker.class.getResourceAsStream("slayer_tasks.json"), StandardCharsets.UTF_8))
		{
			JsonObject icons = new Gson().fromJson(r, JsonObject.class);
			assertEquals(8139, icons.get("kalphites").getAsInt());
			assertEquals(13262, icons.get("abyssal sire").getAsInt());
			icons.keySet().forEach(k -> assertFalse(k, k.startsWith("the ") || !k.equals(k.toLowerCase())));
		}
	}
}
