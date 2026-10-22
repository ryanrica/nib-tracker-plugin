package com.nibtracker;

import static org.junit.Assert.assertEquals;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class FarmMergeTest
{
	private static final long T = 1_441_333L * 1200;

	@Test
	public void feedMissingOrStale()
	{
		Map<String, String> e = new HashMap<>();
		e.put("11062.4774", "32:" + T);
		List<Routines.Patch> local = PatchPredictor.load(new Gson()).predict(e, T * 1000);
		// The feed is older: it still says the herb is empty, but it knows the way there and the run order.
		List<Routines.Patch> feed = Routines.farm(new JsonParser().parse(
			"[{\"region\":\"Catherby\",\"type\":\"Herb\",\"crop\":\"Weeds\",\"state\":\"empty\",\"kind\":\"herb\",\"travel\":\"Catherby teleport\","
				+ "\"travelHow\":\"Cast it\",\"travelCat\":\"SPELLBOOK\",\"travelItem\":\"Law rune\",\"order\":2}]"));

		List<Routines.Patch> merged = FarmRows.merge(local, feed);
		assertEquals(1, merged.size());
		Routines.Patch p = merged.get(0);
		assertEquals("growing", p.state);
		assertEquals("Ranarr", p.crop);
		assertEquals("Catherby teleport", p.travel);
		assertEquals("Cast it", p.travelHow);
		assertEquals("SPELLBOOK", p.travelCat);
		assertEquals("Law rune", p.travelItem);
		assertEquals(2, p.order);

		List<Routines.Patch> alone = FarmRows.merge(local, Collections.emptyList());
		assertEquals(1, alone.size());
		assertEquals("", alone.get(0).travel);

		// A row only the feed has (a special patch with no Time Tracking data) is kept as it is.
		List<Routines.Patch> extra = Routines.farm(new JsonParser().parse(
			"[{\"region\":\"Farming Guild\",\"name\":\"Hespori\",\"crop\":\"\",\"state\":\"unknown\",\"kind\":\"hespori\"}]"));
		List<Routines.Patch> both = FarmRows.merge(local, extra);
		assertEquals(2, both.size());
		assertEquals("Catherby||herb", both.get(0).id());
		assertEquals("unknown", both.get(1).state);
	}
}
