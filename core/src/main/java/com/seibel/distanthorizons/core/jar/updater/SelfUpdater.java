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

package com.seibel.distanthorizons.core.jar.updater;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.jar.EPlatform;
import com.seibel.distanthorizons.core.jar.JarUtils;
import com.seibel.distanthorizons.core.jar.ModGitInfo;
import com.seibel.distanthorizons.core.jar.installer.GitlabGetter;
import com.seibel.distanthorizons.core.jar.installer.ModrinthGetter;
import com.seibel.distanthorizons.core.jar.installer.WebDownloader;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.IVersionConstants;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.coreapi.util.jar.DeleteOnUnlock;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Used to update the mod automatically
 *
 * @author coolGi
 */
public class SelfUpdater
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger(SelfUpdater.class.getSimpleName());
	
	/** As we cannot delete(or replace) the jar while the mod is running, we just have this to delete it once the game closes */
	public static boolean deleteOldOnClose = false;
	
	private static String currentJarSha = "";
	private static String mcVersion = SingletonInjector.INSTANCE.get(IVersionConstants.class).getMinecraftVersion();
	
	public static File newFileLocation;
	
	
	/**
	 * Should be called on the game starting.
	 * (After the config has been initialised)
	 *
	 * @return Whether it should open the update ui
	 */
	public static boolean onStart()
	{
		LOGGER.info("Checking for DH update");
		
		try
		{
			currentJarSha = JarUtils.getFileChecksum(MessageDigest.getInstance("SHA"), JarUtils.jarFile);
		}
		catch (Exception e)
		{
			e.printStackTrace();
			return false;
		}
		
		boolean returnValue = false;
		switch (Config.Client.Advanced.AutoUpdater.updateBranch.get())
		{
			case STABLE:
				returnValue = onStableStart();
				break;
			case NIGHTLY:
				returnValue = onNightlyStart();
				break;
		};
		return returnValue;
	}
	
	public static boolean onStableStart()
	{
		// Some init stuff
		// We use sha1 to check the version as our versioning system is different to the one on modrinth
		if (!ModrinthGetter.init())
			return false;
		if (!ModrinthGetter.mcVersions.contains(mcVersion))
		{
			LOGGER.warn("Minecraft version ["+ mcVersion +"] is not findable on Modrinth, only findable versions are ["+ ModrinthGetter.mcVersions.toString() +"]");
			return false;
		}
		
		// Check the sha's of both our stuff
		if (currentJarSha.equals(ModrinthGetter.getLatestShaForVersion(mcVersion)))
			return false;
		
		
		LOGGER.info("New version (" + ModrinthGetter.getLatestNameForVersion(mcVersion) + ") of " + ModInfo.READABLE_NAME + " is available");
		newFileLocation = JarUtils.jarFile.getParentFile().toPath().resolve("update").resolve(ModInfo.NAME + "-" + ModrinthGetter.getLatestNameForVersion(mcVersion) + ".jar").toFile();
		if (Config.Client.Advanced.AutoUpdater.enableSilentUpdates.get())
		{
			// Auto-update mod
			updateMod(mcVersion, newFileLocation);
			return false;
		} // else
		return true;
	}
	
	public static boolean onNightlyStart()
	{
		if (GitlabGetter.INSTANCE.projectPipelines.size() == 0)
			return false;
		com.electronwill.nightconfig.core.Config pipeline = GitlabGetter.INSTANCE.projectPipelines.get(0);
		
		if (!pipeline.get("ref").equals(ModGitInfo.Git_Main_Branch))
		{
			//LOGGER.warn("Latest pipeline was found for branch ["+ pipeline.get("ref") +"], but we are on branch ["+ ModGitInfo.Git_Main_Branch +"].");
			return false;
		}
		
		if (!pipeline.get("status").equals("success"))
		{
			LOGGER.warn("Pipeline for branch ["+ ModGitInfo.Git_Main_Branch +"], commit ["+ pipeline.get("id") +"], has either failed to build, or still building.");
			return false;
		}
		
		if (!GitlabGetter.INSTANCE.getDownloads(pipeline.get("id")).containsKey(mcVersion))
		{
			LOGGER.warn("Minecraft version ["+ mcVersion +"] is not findable on Gitlab, findable versions are ["+ GitlabGetter.INSTANCE.getDownloads(pipeline.get("id")).keySet().toArray().toString() +"].");
			return false;
		}
		
		String latestCommit = pipeline.get("sha");
		
		if (ModGitInfo.Git_Main_Commit.equals(latestCommit)) // If we are already on the latest commit, then dont update
			return false;
		
		
		LOGGER.info("New version (" + latestCommit + ") of " + ModInfo.READABLE_NAME + " is available");
		newFileLocation = JarUtils.jarFile.getParentFile().toPath().resolve("update").resolve(ModInfo.NAME + "-" + latestCommit + ".jar").toFile();
		if (Config.Client.Advanced.AutoUpdater.enableSilentUpdates.get())
		{
			// Auto-update mod
			updateMod(mcVersion, newFileLocation);
			return false;
		}
		return true;
	}
	
	
	
	
	public static boolean updateMod()
	{
		String mcVer = SingletonInjector.INSTANCE.get(IVersionConstants.class).getMinecraftVersion();
		return updateMod(
				mcVer,
				newFileLocation
		);
	}
	public static boolean updateMod(String minecraftVersion, File file)
	{
		boolean returnValue = false;
		switch (Config.Client.Advanced.AutoUpdater.updateBranch.get())
		{
			case STABLE:
				returnValue = updateStableMod(minecraftVersion, file);
				break;
			case NIGHTLY:
				returnValue = updateNightlyMod(minecraftVersion, file);
				break;
		};
		return returnValue;
	}
	
	public static boolean updateStableMod(String minecraftVersion, File file)
	{
		try
		{
			LOGGER.info("Attempting to auto update " + ModInfo.READABLE_NAME);
			
			Files.createDirectories(file.getParentFile().toPath());
			WebDownloader.downloadAsFile(ModrinthGetter.getLatestDownloadForVersion(minecraftVersion), file);
			
			// Check if the checksum of the downloaded jar is correct (not required, but good to have to prevent corruption or interception)
			if (!JarUtils.getFileChecksum(MessageDigest.getInstance("SHA"), file).equals(ModrinthGetter.getLatestShaForVersion(minecraftVersion)))
			{
				LOGGER.warn("DH update checksum failed, aborting install");
				throw new Exception("Checksum failed");
			}
			
			deleteOldOnClose = true;
			
			LOGGER.info(ModInfo.READABLE_NAME + " successfully updated. It will apply on game's relaunch");
			new Thread(() -> {
				System.setProperty("java.awt.headless", "false"); // Required to make it work
				JOptionPane.showMessageDialog(null, ModInfo.READABLE_NAME + " updated, this will be applied on game restart.", ModInfo.READABLE_NAME, JOptionPane.INFORMATION_MESSAGE);
			}).start();
			return true;
		}
		catch (Exception e)
		{
			LOGGER.warn("Failed to update " + ModInfo.READABLE_NAME + " to version " + ModrinthGetter.getLatestNameForVersion(minecraftVersion));
			e.printStackTrace();
			return false;
		}
	}
	
	public static boolean updateNightlyMod(String minecraftVersion, File file)
	{
		if (GitlabGetter.INSTANCE.projectPipelines.size() == 0)
			return false;
		
		try
		{
			LOGGER.info("Attempting to auto update " + ModInfo.READABLE_NAME);
			
			Files.createDirectories(file.getParentFile().toPath());
			
			File mergedZip = file.getParentFile().toPath().resolve("merged.zip").toFile();
			
			WebDownloader.downloadAsFile(GitlabGetter.INSTANCE.getDownloads(GitlabGetter.INSTANCE.projectPipelines.get(0).get("id")).get(minecraftVersion), mergedZip);
			
			ZipInputStream zis = new ZipInputStream(new FileInputStream(mergedZip));
			ZipEntry zipEntry = zis.getNextEntry();
			while (zipEntry != null)
			{
				if (!zipEntry.isDirectory() && zipEntry.getName().contains("Merged")) // Look until the merged jar is found
				{
					// write file content
					FileOutputStream fos = new FileOutputStream(file);
					byte[] buffer = new byte[1024];
					
					int len;
					while ((len = zis.read(buffer)) > 0) {
						fos.write(buffer, 0, len);
					}
					fos.close();
					
					deleteOldOnClose = true;
					
					LOGGER.info(ModInfo.READABLE_NAME + " successfully updated. It will apply on game's relaunch");
					new Thread(() -> {
						System.setProperty("java.awt.headless", "false"); // Required to make it work
						JOptionPane.showMessageDialog(null, ModInfo.READABLE_NAME + " updated, this will be applied on game restart.", ModInfo.READABLE_NAME, JOptionPane.INFORMATION_MESSAGE);
					}).start();
					
					zis.close();
					Files.deleteIfExists(newFileLocation.getParentFile().toPath().resolve("merged.zip"));
					
					return true;
				}
				
				zipEntry = zis.getNextEntry();
			}
			zis.close();
			
			return false;
		}
		catch (Exception e)
		{
			LOGGER.warn("Failed to update " + ModInfo.READABLE_NAME + " to version " + GitlabGetter.INSTANCE.projectPipelines.get(0).get("sha"));
			e.printStackTrace();
			return false;
		}
	}
	
	
	
	
	
	/**
	 * Should be called when the game is closed.
	 * This is ued to delete the previous file if it is required at the end.
	 */
	public static void onClose()
	{
		if (deleteOldOnClose)
		{
			try
			{
				Files.move(newFileLocation.toPath(), JarUtils.jarFile.getParentFile().toPath().resolve(newFileLocation.getName()));
				Files.delete(newFileLocation.getParentFile().toPath());
			}
			catch (Exception e)
			{
				LOGGER.warn("Failed to move updated fire from [" + newFileLocation.getAbsolutePath() + "] to [" + JarUtils.jarFile.getParentFile().getAbsolutePath() + "], please move it manually");
				e.printStackTrace();
			}
			try
			{
				if (EPlatform.get() != EPlatform.WINDOWS)
				{
					Files.delete(JarUtils.jarFile.toPath());
				}
				else
				{
					// Gets the Java binary
					String javaHome = System.getProperty("java.home");
					String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
					
					// Execute the new jar, to delete the old jar once it detects the lock has been lifted
					Runtime.getRuntime().exec(
							"\""+ javaBin +"\" -cp \""+ 
									newFileLocation.getAbsolutePath()
									+"\" "+ 
									DeleteOnUnlock.class.getCanonicalName() 
									+" "+
									URLEncoder.encode(JarUtils.jarFile.getAbsolutePath(), "UTF-8") // Encode the file location so that it doesnt have any spaces
					);
				}
			}
			catch (Exception e)
			{
				LOGGER.warn("Failed to delete previous " + ModInfo.READABLE_NAME + " file, please delete it manually at [" + JarUtils.jarFile + "]");
				e.printStackTrace();
			}
		}
	}
}
