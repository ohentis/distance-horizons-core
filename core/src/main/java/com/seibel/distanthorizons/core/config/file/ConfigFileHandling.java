package com.seibel.distanthorizons.core.config.file;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.config.ConfigBase;
import com.seibel.distanthorizons.core.config.types.AbstractConfigType;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Handles reading and writing config files.
 *
 * @author coolGi
 * @version 2023-7-16
 */
public class ConfigFileHandling
{
	private static final Logger LOGGER = ConfigBase.LOGGER;
	
	public final ConfigBase configBase;
	public final Path configPath;
	
	public ConfigFileHandling(ConfigBase configBase)
	{
		this.configBase = configBase;
		configPath = SingletonInjector.INSTANCE.get(IMinecraftSharedWrapper.class)
				.getInstallationDirectory().toPath().resolve("config").resolve(this.configBase.modName + ".toml");
	}
	
	/** Saves the entire config to the file */
	public void saveToFile()
	{
		CommentedFileConfig config = CommentedFileConfig.builder(configPath.toFile()).build();
		if (!Files.exists(configPath)) // Try to check if the config exists
			try
			{
				if (!this.configPath.getParent().toFile().exists())
				{
					Files.createDirectory(this.configPath.getParent());
				}
				Files.createFile(configPath);
			}
			catch (IOException ex)
			{
				ex.printStackTrace();
			}
		
		loadConfig(config);
		
		for (AbstractConfigType<?, ?> entry : this.configBase.entries)
		{
			if (ConfigEntry.class.isAssignableFrom(entry.getClass()))
			{
				createComment((ConfigEntry<?>) entry, config);
				saveEntry((ConfigEntry<?>) entry, config);
			}
		}
		
		
		try
		{
			config.save();
		}
		catch (Exception e)
		{
			// If it fails to save, crash game
			SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class).crashMinecraft("Failed to save config at [" + configPath.toString() + "]", e);
		}
		config.close();
	}
	/**
	 * Loads the entire config from the file
	 *
	 * @apiNote This overwrites any value currently stored in the config
	 */
	public void loadFromFile()
	{
		CommentedFileConfig config = CommentedFileConfig.builder(configPath.toFile()).build();
		// Attempt to load the file and if it fails then save config to file
		try
		{
			if (Files.exists(configPath))
				config.load();
			else
			{
				saveToFile();
				return;
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
			saveToFile();
			return;
		}
		
		// Load all the entries
		for (AbstractConfigType<?, ?> entry : this.configBase.entries)
		{
			if (ConfigEntry.class.isAssignableFrom(entry.getClass()))
			{
				createComment((ConfigEntry<?>) entry, config);
				loadEntry((ConfigEntry<?>) entry, config);
			}
		}
		
		
		try
		{
			config.save();
		}
		catch (Exception e)
		{
			// If it fails to save, crash game
			SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class).crashMinecraft("Failed to save config at [" + configPath.toString() + "]", e);
		}
		config.close();
	}
	
	
	
	
	// Save an entry when only given the entry
	public void saveEntry(ConfigEntry<?> entry)
	{
		CommentedFileConfig config = CommentedFileConfig.builder(configPath.toFile()).build();
		loadConfig(config);
		saveEntry(entry, config);
		config.save();
		config.close();
	}
	// Save an entry
	@SuppressWarnings("unchecked")
	public void saveEntry(ConfigEntry<?> entry, CommentedFileConfig workConfig)
	{
		if (!entry.getAppearance().showInFile) return;
		if (entry.getTrueValue() == null)
			throw new IllegalArgumentException("Entry [" + entry.getNameWCategory() + "] is null, this may be a problem with [" + configBase.modName + "]. Please contact the authors");
		
		Class<?> originalClass = ConfigTypeConverters.isClassConvertable(entry.getType());
		if (originalClass != null)
		{
			workConfig.set(entry.getNameWCategory(), ConfigTypeConverters.convertToString(originalClass, entry.getTrueValue()));
			return;
		}
		workConfig.set(entry.getNameWCategory(), entry.getTrueValue());
	}
	
	// Loads an entry when only given the entry
	public void loadEntry(ConfigEntry<?> entry)
	{
		CommentedFileConfig config = CommentedFileConfig.builder(configPath.toFile()).autosave().build();
		loadConfig(config);
		loadEntry(entry, config);
		config.close();
		
	}
	// Loads an entry
	@SuppressWarnings("unchecked") // Suppress due to its always safe
	public <T> void loadEntry(ConfigEntry<T> entry, CommentedFileConfig workConfig)
	{
		if (!entry.getAppearance().showInFile) return;
		
		if (workConfig.contains(entry.getNameWCategory()))
		{
			try
			{
				if (entry.getType().isEnum())
				{
					entry.setWithoutSaving((T) (workConfig.getEnum(entry.getNameWCategory(), (Class<? extends Enum>) entry.getType())));
					return;
				}
				Class<?> originalClass = ConfigTypeConverters.isClassConvertable(entry.getType());
				if (originalClass != null)
				{
					entry.setWithoutSaving((T) ConfigTypeConverters.convertFromString(originalClass, workConfig.get(entry.getNameWCategory())));
					return;
				}
				
				if (entry.getType() == workConfig.get(entry.getNameWCategory()).getClass())
				{ // If the types are the same
					entry.setWithoutSaving((T) workConfig.get(entry.getNameWCategory()));
					entry.clampWithinRange();
					return;
				}
				
				LOGGER.warn("Entry [" + entry.getNameWCategory() + "] is invalid. Expected " + entry.getType() + " but got " + workConfig.get(entry.getNameWCategory()).getClass() + ". Using default value.");
				saveEntry(entry, workConfig);
			}
			catch (Exception e)
			{
//                e.printStackTrace();
				LOGGER.warn("Entry [" + entry.getNameWCategory() + "] had an invalid value when loading the config. Using default value.");
				saveEntry(entry, workConfig);
			}
		}
		else
		{
			saveEntry(entry, workConfig);
		}
	}
	
	// Creates the comment for an entry when only given the entry
	public void createComment(ConfigEntry<?> entry)
	{
		CommentedFileConfig config = CommentedFileConfig.builder(configPath.toFile()).autosave().build();
		loadConfig(config);
		createComment(entry, config);
		config.close();
	}
	// Creates a comment for an entry
	public void createComment(ConfigEntry<?> entry, CommentedFileConfig workConfig)
	{
		if (!entry.getAppearance().showInFile)
			return;
		if (entry.getComment() != null)
		{
			workConfig.setComment(entry.getNameWCategory(), " " + entry.getComment().replaceAll("\n", "\n ") + "\n ");
		}
	}
	
	
	
	
	/** Does config.load(); but with error checking */
	public void loadConfig(CommentedFileConfig config)
	{
		try
		{
			config.load();
		}
		catch (Exception e)
		{
			System.out.println("Loading file failed because of this expectation:\n" + e);
			try
			{ // Now try remaking the file and loading it
				if (!this.configPath.getParent().toFile().exists())
				{
					Files.createDirectory(this.configPath.getParent());
				}
				
				try
				{
					boolean fileDeleted = Files.deleteIfExists(this.configPath);
					System.out.println("File at [" + this.configPath + "] was " + (fileDeleted ? "" : "not ") + "able to be deleted.");
				}
				catch (AccessDeniedException ignored) { /* temporary fix due to windows/Intellij issues either locking or changing the permissions of the file */ }
				
				
				try
				{
					Files.createFile(this.configPath);	
				}
				catch (FileAlreadyExistsException ignore) { /* temporary fix due to windows/Intellij issues either locking or changing the permissions of the file */ }
				
				config.load();
			}
			catch (IOException ex)
			{
				System.out.println("Creating file failed");
				ex.printStackTrace();
				SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class).crashMinecraft("Loading file and resetting config file failed at path [" + configPath + "]. Please check the file is ok and you have the permissions", ex);
			}
		}
	}
	
	
	// ========== API (server) STUFF ========== //
	/** ALWAYS CLEAR WHEN NOT ON SERVER!!!! */
	// We are not using this stuff, so comment it out for now (if we ever do need it then we can uncomment it)
    /*
    @SuppressWarnings("unchecked")
    public static void clearApiValues() {
        for (AbstractConfigType<?, ?> entry : ConfigBase.entries) {
            if (ConfigEntry.class.isAssignableFrom(entry.getClass()) && ((ConfigEntry) entry).allowApiOverride) {
                ((ConfigEntry) entry).setApiValue(null);
            }
        }
    }
    @SuppressWarnings("unchecked")
    public static String exportApiValues() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("configVersion", ConfigBase.configVersion);
        for (AbstractConfigType<?, ?> entry : ConfigBase.entries) {
            if (ConfigEntry.class.isAssignableFrom(entry.getClass()) && ((ConfigEntry) entry).allowApiOverride) {
                if (ConfigTypeConverters.convertObjects.containsKey(entry.getType())) {
                    jsonObject.put(entry.getNameWCategory(), ConfigTypeConverters.convertToString(entry.getType(), ((ConfigEntry<?>) entry).getTrueValue()));
                } else {
                    jsonObject.put(entry.getNameWCategory(), ((ConfigEntry<?>) entry).getTrueValue());
                }
            }
        }
        return jsonObject.toJSONString();
    }
    @SuppressWarnings("unchecked") // Suppress due to its always safe
    public static void importApiValues(String values) {
        JSONObject jsonObject = null;
        try {
            jsonObject = (JSONObject) new JSONParser().parse(values);
        } catch (ParseException p) {
            p.printStackTrace();
        }

        // Importing code
        for (AbstractConfigType<?, ?> entry : ConfigBase.entries) {
            if (ConfigEntry.class.isAssignableFrom(entry.getClass()) && ((ConfigEntry) entry).allowApiOverride) {
                Object jsonItem = jsonObject.get(entry.getNameWCategory());
                if (entry.getType().isEnum()) {
                    ((ConfigEntry) entry).setApiValue(Enum.valueOf((Class<? extends Enum>) entry.getType(), jsonItem.toString()));
                } else if (ConfigTypeConverters.convertObjects.containsKey(entry.getType())) {
                    ((ConfigEntry) entry).setApiValue(ConfigTypeConverters.convertFromString(entry.getType(), jsonItem.toString()));
                } else {
                    ((ConfigEntry) entry).setApiValue(jsonItem);
                }
            }
        }
    }
     */
}
