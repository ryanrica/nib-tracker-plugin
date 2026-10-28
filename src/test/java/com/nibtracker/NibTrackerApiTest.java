package com.nibtracker;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

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
}
