/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020-2023 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.jar.installer;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Gets the releases available on gitlab and sends out the link.
 * Please move over to ModrinthGetter for downloading releases of the mod
 *
 * @author coolGi
 */
// TODO: Change this to a way to get the nightly builds
@Deprecated
public class GitlabGetter
{
	public static final String GitLabApi = "https://gitlab.com/api/v4/projects/";
	public static final String projectID = "18204078";
//    public static JSONArray projectRelease = new JSONArray();
	
	public static List<String> releaseNames = new ArrayList<>(); // This list contains the release ID's
	public static List<String> readableReleaseNames = new ArrayList<>(); // This list contains the readable names of the ID's
	private static List<String> mcVersionReleases = new ArrayList<>(); // A list of all minecraft releases
	
	
	
	public static void init()
	{
//        try {
//            // TODO: Modify the projectRelease to fix 1.6.0a's versions rather than fixing it everytime we want to use projectReleases
//            projectRelease = (JSONArray) new JSONParser().parse(WebDownloader.downloadAsString(new URL(GitLabApi+projectID+"/releases")));
//
//            for (int i = 0; i < projectRelease.size(); i++) {
//                JSONObject currentRelease = (JSONObject) projectRelease.get(i);
//                if (!currentRelease.get("tag_name").toString().contains("-1.6.0a")) { // We have to do this cus 1.6.0a stuffed up some ordering
//                    releaseNames.add(currentRelease.get("tag_name").toString());
//                    if (currentRelease.get("tag_name").toString().startsWith("1.16.4") || currentRelease.get("tag_name").toString().startsWith("1.16.5")) {
//                        // We want to do this to remove the mc version from the start of the name in 1.5.4 and prior
//                        readableReleaseNames.add(currentRelease.get("name").toString().replace("1.16.4 ","").replace("1.16.5 ",""));
//                    } else {
//                        readableReleaseNames.add(currentRelease.get("name").toString());
//                    }
//                } else if (!releaseNames.contains("1.6.0a")) {
//                    releaseNames.add("1.6.0a");
//                    readableReleaseNames.add("Alpha 1.6.0");
//                }
//            }
//
//            // Some tests for getting the release versions
////            System.out.println(getRelease("1.6.3a", "1.18.2"));
////            System.out.println(getRelease("1.16.4-a1.2", null)); // The oldest downloadable version is 1.2 as versions before that didn't include downloads
//
//            // Set the mcVersionReleases
//            JSONArray minecraftReleases = (JSONArray) ((JSONObject) new JSONParser().parse(WebDownloader.downloadAsString(new URL("https://launchermeta.mojang.com/mc/game/version_manifest.json")))).get("versions");
//            for (int i = 0; i < minecraftReleases.size(); i++) {
//                JSONObject jsonObject = (JSONObject) minecraftReleases.get(i);
//                if (jsonObject.get("type").toString().equals("release"))
//                    mcVersionReleases.add(jsonObject.get("id").toString());
//            }
//
//            // Some tests to get minecraft versions available in that version of the mod
////            System.out.println(getMcVersionsInRelease("1.6.5a"));
////            System.out.println(getMcVersionsInRelease("1.16.4-a1.2"));
//        } catch (Exception e) { e.printStackTrace(); }
	}
	
	/** Gets the compatible minecraft versions a release of the mod works with */
	public static List<String> getMcVersionsInRelease(String version)
	{
		List<String> versions = new ArrayList<>();
//
//        JSONArray releaseArray = getScuffedReleaseArray(version);
//
//
//        for (int i = 0; i < releaseArray.size(); i++) {
//            String name = ((JSONObject) releaseArray.get(i)).get("name").toString();
//            for (String mcVersion : mcVersionReleases) {
//                if (name.contains(mcVersion)) {
//                    versions.add(mcVersion);
//                    break;
//                }
//            }
//        }
//
//        // Sort it so the newest versions of minecraft are at the top
//        Collections.sort(versions);
//        Collections.reverse(versions);
		
		return versions;
	}
	/** Gets the url to the download of a release of the mod */
	public static URL getRelease(String version, String mcVersion)
	{
//        JSONArray releaseArray = getScuffedReleaseArray(version);
//
//        if (mcVersion != null) {
//            for (int i = 0; i < releaseArray.size(); i++) {
//                if (((JSONObject) releaseArray.get(i)).get("name").toString().contains(mcVersion)) { // With the way our GitLab releases is set up, the only way to check the mc version is to check if it is in the name
//                    try {
//                        return new URL(((JSONObject) releaseArray.get(i)).get("direct_asset_url").toString());
//                    } catch (Exception e) { e.printStackTrace(); }
//                }
//            }
//        } else {
//            // If version is null it gets the first version available
//            try {
//                return new URL(((JSONObject) releaseArray.get(0)).get("direct_asset_url").toString());
//            } catch (Exception e) { e.printStackTrace(); }
//        }
		
		return null;
	}
	/** Gets the update log of a release */
	public static String getVersionDescription(String version)
	{
//        try {
//            if (!version.equals("1.6.0a")) { // We have to do this cus 1.6.0a stuffed up some ordering
//                // Do this hack to remove all the mcVer-1.6.0a items from the releaseNames
//                int newVer = releaseNames.indexOf(version);
//                if (releaseNames.indexOf(version) > releaseNames.size()-14)
//                    newVer += 2;
//
//                return ((JSONObject) projectRelease.get(newVer)).get("description").toString();
//            } else {
//                for (int i = 0; i < projectRelease.size(); i++) {
//                    JSONObject currentRelease = ((JSONObject) new JSONParser().parse(projectRelease.get(i).toString()));
//                    if (currentRelease.get("tag_name").toString().contains("-1.6.0a"))
//                        return currentRelease.get("description").toString();
//                }
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
		return null;
	}


//    public static JSONArray getScuffedReleaseArray(String version) {
//        // Get the asset links of the releases
//        JSONArray releaseArray = new JSONArray();
//
//        if (!version.equals("1.6.0a")) { // We have to do this cus 1.6.0a stuffed up some ordering
//            try {
//                // Do this hack to remove all the mcVer-1.6.0a items from the releaseNames
//                int newVer = releaseNames.indexOf(version);
//                if (releaseNames.indexOf(version) > releaseNames.size()-14)
//                    newVer += 2;
//
//                releaseArray = (
//                        ((JSONArray)
//                                ((JSONObject)
//                                        ((JSONObject)
//                                                projectRelease.get(newVer)
//                                        ).get("assets")
//                                ).get("links")));
//            } catch (Exception e) {
//                System.out.println("ERROR: Release [" + version + "] is not a valid release. Printing stacktrace...");
//                e.printStackTrace();
//                return null;
//            }
//        } else {
//            try {
//                for (int i = 0; i < projectRelease.size(); i++) {
//                    JSONObject currentRelease = ((JSONObject) new JSONParser().parse(projectRelease.get(i).toString()));
//                    if (currentRelease.get("tag_name").toString().contains("-1.6.0a")) {
//                        releaseArray.add(
//                                ((JSONArray)
//                                    ((JSONObject)
//                                        currentRelease.get("assets")
//                                    ).get("links")
//                                ).get(0)
//                        );
//                    }
//                }
//            } catch (Exception e) {
//                e.printStackTrace();
//                return null;
//            }
//        }
//
//        return releaseArray;
//    }
}
