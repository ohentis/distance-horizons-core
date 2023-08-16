package com.seibel.distanthorizons.core.jar.installer;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.json.JsonFormat;

import java.net.URL;
import java.util.*;

/**
 * Gets the releases available on Modrinth and allows you to perform actions with them
 *
 * @author coolGi
 */
// TODO: Fix stuff in here (check how to do stuff with `[{jsonStuff},{jsonStuff}]` which needs to be remade with nightconfig's json
public class ModrinthGetter
{
	public static final String ModrinthAPI = "https://api.modrinth.com/v2/project/";
	public static final String projectID = "distanthorizons";
	public static boolean initted = false;
	public static ArrayList<Config> projectRelease;
	public static Map<String, Config> idToJson = new HashMap<>();
	
	public static List<String> releaseID = new ArrayList<>(); // This list contains the release ID's
	public static List<String> mcVersions = new ArrayList<>(); // List of available Minecraft versions in the mod
	/**
	 * Arg 1 = Release ID;
	 * Arg 2 = Readable name
	 */
	public static Map<String, String> releaseNames = new HashMap<>(); // This list contains the readable names of the ID's to the
	/**
	 * Arg 1 = Minecraft version;
	 * Arg 2 = Compatible project ID's for that
	 */
	public static Map<String, List<String>> mcVerToReleaseID = new HashMap<>();
	/**
	 * Arg 1 = ID;
	 * Arg 2 = Download URL
	 */
	public static Map<String, URL> downloadUrl = new HashMap<>(); // Get the download url
	/**
	 * Arg 1 = ID;
	 * Arg 2 = Changelog
	 */
	public static Map<String, String> changeLogs = new HashMap<>();
	
	
	public static boolean init()
	{
		try
		{
			initted = false;
			projectRelease = JsonFormat.fancyInstance().createParser().parse("{\"E\":" + WebDownloader.downloadAsString(new URL(ModrinthAPI + projectID + "/version")) + "}").get("E");
			
			
			for (Config currentRelease : projectRelease)
			{
				String workingID = currentRelease.get("id").toString();
				
				releaseID.add(workingID);
				idToJson.put(workingID, currentRelease);
				releaseNames.put(workingID, currentRelease.get("name").toString().replaceAll(" - 1\\..*", ""));
				changeLogs.put(workingID, currentRelease.get("changelog").toString());
				try
				{
					downloadUrl.put(workingID,
							new URL(
									((Config)
											((ArrayList) currentRelease.get("files"))
													.get(0))
											.get("url")
											.toString()
							));
				}
				catch (Exception e)
				{
					e.printStackTrace();
				}
				
				// Get all the mc versions this mod is available for
				for (String mcVer : (List<String>) currentRelease.get("game_versions"))
				{
					if (!mcVersions.contains(mcVer))
					{
						mcVersions.add(mcVer);
						mcVerToReleaseID.put(mcVer, new ArrayList<>());
					}
					mcVerToReleaseID.get(mcVer).add(workingID);
				}
			}
			// Sort them to look better
			Collections.sort(mcVersions);
			Collections.reverse(mcVersions);
			
			initted = true;
			return true;
		}
		catch (Exception e)
		{
			e.printStackTrace();
			return false;
		}
	}
	
	public static String getLatestIDForVersion(String mcVer)
	{
		return mcVerToReleaseID.get(mcVer).get(0);
	}
	public static String getLatestNameForVersion(String mcVer)
	{
		return releaseNames.get(mcVerToReleaseID.get(mcVer).get(0));
	}
	public static URL getLatestDownloadForVersion(String mcVer)
	{
		return downloadUrl.get(mcVerToReleaseID.get(mcVer).get(0));
	}
	public static String getLatestShaForVersion(String mcVer)
	{
		return (((ArrayList<Config>) idToJson.get(
				mcVerToReleaseID.get(mcVer).get(0)
		).get("files")).get(0).get("hashes.sha1")
				.toString());
	}
	
}
