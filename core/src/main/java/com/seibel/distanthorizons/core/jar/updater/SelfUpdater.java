package com.seibel.distanthorizons.core.jar.updater;

import com.seibel.distanthorizons.core.jar.JarUtils;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.jar.installer.ModrinthGetter;
import com.seibel.distanthorizons.core.jar.installer.WebDownloader;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.IVersionConstants;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

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
	public static File newFileLocation;
	
	/**
	 * Should be called on the game starting.
	 * (After the config has been initialised)
	 *
	 * @return Whether it should open the update ui
	 */
	public static boolean onStart()
	{
		// Some init stuff
		// We use sha1 to check the version as our versioning system is different to the one on modrinth
		if (!ModrinthGetter.init()) return false;
		String jarSha = "";
		try
		{
			jarSha = JarUtils.getFileChecksum(MessageDigest.getInstance("SHA"), JarUtils.jarFile);
		}
		catch (Exception e)
		{
			e.printStackTrace();
			return false;
		}
		String mcVersion = SingletonInjector.INSTANCE.get(IVersionConstants.class).getMinecraftVersion();
		
		// Check the sha's of both our stuff
		if (jarSha.equals(ModrinthGetter.getLatestShaForVersion(mcVersion)))
			return false;
		
		
		LOGGER.info("New version (" + ModrinthGetter.getLatestNameForVersion(mcVersion) + ") of " + ModInfo.READABLE_NAME + " is available");
		if (Config.Client.Advanced.AutoUpdater.enableSilentUpdates.get())
		{
			newFileLocation = JarUtils.jarFile.getParentFile().toPath().resolve("update").resolve(ModInfo.NAME + "-" + ModrinthGetter.getLatestNameForVersion(mcVersion) + ".jar").toFile();
			// Auto-update mod
			updateMod(mcVersion, newFileLocation);
			return false;
		} // else
		return true;
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
				Files.delete(JarUtils.jarFile.toPath());
			}
			catch (Exception e)
			{
				LOGGER.warn("Failed to delete previous " + ModInfo.READABLE_NAME + " file, please delete it manually at [" + JarUtils.jarFile + "]");
				e.printStackTrace();
			}
		}
	}
	
	public static boolean updateMod()
	{
		String mcVer = SingletonInjector.INSTANCE.get(IVersionConstants.class).getMinecraftVersion();
		newFileLocation = JarUtils.jarFile.getParentFile().toPath().resolve("update").resolve(ModInfo.NAME + "-" + ModrinthGetter.getLatestNameForVersion(mcVer) + ".jar").toFile();
		return updateMod(
				mcVer,
				newFileLocation
		);
	}
	public static boolean updateMod(String minecraftVersion, File file)
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
	
}
